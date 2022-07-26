// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.
package com.starrocks.sql.optimizer.statistics;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.google.common.collect.Lists;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.statistic.StatisticExecutor;
import com.starrocks.thrift.TStatisticData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

import static com.starrocks.sql.optimizer.Utils.getLongFromDateTime;

public class ColumnHistogramStatsCacheLoader implements AsyncCacheLoader<ColumnStatsCacheKey, Optional<Histogram>> {
    private static final Logger LOG = LogManager.getLogger(ColumnBasicStatsCacheLoader.class);
    private final StatisticExecutor statisticExecutor = new StatisticExecutor();
    private static DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    public @NonNull
    CompletableFuture<Optional<Histogram>> asyncLoad(@NonNull ColumnStatsCacheKey cacheKey,
                                                     @NonNull Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<TStatisticData> statisticData =
                        queryHistogramStatistics(cacheKey.tableId, Lists.newArrayList(cacheKey.column));
                // check TStatisticData is not empty, There may be no such column Statistics in BE
                if (!statisticData.isEmpty()) {
                    return Optional.of(convert2Histogram(statisticData.get(0)));
                } else {
                    return Optional.empty();
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<@NonNull ColumnStatsCacheKey, @NonNull Optional<Histogram>>> asyncLoadAll(
            @NonNull Iterable<? extends @NonNull ColumnStatsCacheKey> keys, @NonNull Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            Map<ColumnStatsCacheKey, Optional<Histogram>> result = new HashMap<>();
            try {
                long tableId = -1;
                List<String> columns = new ArrayList<>();
                for (ColumnStatsCacheKey key : keys) {
                    tableId = key.tableId;
                    columns.add(key.column);
                }
                List<TStatisticData> histogramStatsDataList = queryHistogramStatistics(tableId, columns);
                for (TStatisticData histogramStatsData : histogramStatsDataList) {
                    Histogram histogram = convert2Histogram(histogramStatsData);
                    result.put(new ColumnStatsCacheKey(histogramStatsData.tableId, histogramStatsData.columnName),
                            Optional.of(histogram));
                }

                return result;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<Histogram>> asyncReload(
            @NonNull ColumnStatsCacheKey key, @NonNull Optional<Histogram> oldValue,
            @NonNull Executor executor) {
        return asyncLoad(key, executor);
    }

    public List<TStatisticData> queryHistogramStatistics(long tableId, List<String> column) throws Exception {
        return statisticExecutor.queryHistogram(tableId, column);
    }

    private Histogram convert2Histogram(TStatisticData statisticData) throws AnalysisException {
        Database db = GlobalStateMgr.getCurrentState().getDb(statisticData.dbId);
        if (db == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_DB_ERROR, statisticData.dbId);
        }
        Table table = db.getTable(statisticData.tableId);
        if (!(table instanceof OlapTable)) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_TABLE_ERROR, statisticData.tableId);
        }
        Column column = table.getColumn(statisticData.columnName);
        if (column == null) {
            ErrorReport.reportAnalysisException(ErrorCode.ERR_BAD_FIELD_ERROR, statisticData.columnName);
        }

        List<Bucket> buckets = convertBuckets(statisticData.histogram, column.getType());
        Map<Double, Long> topn = convertTopN(statisticData.histogram, column.getType());
        return new Histogram(buckets, topn);
    }

    private List<Bucket> convertBuckets(String histogramString, Type type) {
        JsonObject jsonObject = JsonParser.parseString(histogramString).getAsJsonObject();
        JsonArray histogramObj = jsonObject.getAsJsonArray("buckets");

        List<Bucket> buckets = Lists.newArrayList();
        for (int i = 0; i < histogramObj.size(); ++i) {
            JsonArray bucketJsonArray = histogramObj.get(i).getAsJsonArray();

            double low;
            double high;
            if (type.isDate()) {
                low = (double) getLongFromDateTime(
                        LocalDate.parse(bucketJsonArray.get(0).getAsString(), dateFormatter).atStartOfDay());
                high = (double) getLongFromDateTime(
                        LocalDate.parse(bucketJsonArray.get(1).getAsString(), dateFormatter).atStartOfDay());
            } else if (type.isDatetime()) {
                low = (double) getLongFromDateTime(LocalDateTime.parse(bucketJsonArray.get(0).getAsString(), dateTimeFormatter));
                high = (double) getLongFromDateTime(LocalDateTime.parse(bucketJsonArray.get(1).getAsString(), dateTimeFormatter));
            } else {
                low = Double.parseDouble(bucketJsonArray.get(0).getAsString());
                high = Double.parseDouble(bucketJsonArray.get(1).getAsString());
            }

            Bucket bucket = new Bucket(low, high,
                    Long.parseLong(bucketJsonArray.get(2).getAsString()),
                    Long.parseLong(bucketJsonArray.get(3).getAsString()));
            buckets.add(bucket);
        }
        return buckets;
    }

    private Map<Double, Long> convertTopN(String histogramString, Type type) {
        JsonObject jsonObject = JsonParser.parseString(histogramString).getAsJsonObject();
        JsonArray histogramObj = jsonObject.getAsJsonArray("top-n");

        Map<Double, Long> topN = new HashMap<>();
        for (int i = 0; i < histogramObj.size(); ++i) {
            JsonArray bucketJsonArray = histogramObj.get(i).getAsJsonArray();

            double key;
            if (type.isDate()) {
                key = (double) getLongFromDateTime(
                        LocalDate.parse(bucketJsonArray.get(0).getAsString(), dateFormatter).atStartOfDay());
            } else if (type.isDatetime()) {
                key = (double) getLongFromDateTime(LocalDateTime.parse(bucketJsonArray.get(0).getAsString(), dateTimeFormatter));
            } else {
                key = Double.parseDouble(bucketJsonArray.get(0).getAsString());
            }

            topN.put(key, Long.parseLong(bucketJsonArray.get(1).getAsString()));
        }
        return topN;
    }
}