// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.sql.optimizer.cost;

import com.starrocks.qe.ConnectContext;
import com.starrocks.sql.optimizer.ExpressionContext;
import com.starrocks.sql.optimizer.base.PhysicalPropertySet;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.statistics.Statistics;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * For different distributed join execution methods, due to their different execution characteristics,
 * they should have different cost. We currently have four execution modes: broadcast join,
 * bucket_shuffle join, shuffle join, and colocate join. The latter three are essentially shuffle joins,
 * so their execution efficiency can be approximately regarded as consistent. The implementation of
 * broadcast join is a little different from the other three.
 * 1. In the build hash table stage of broadcast join, the parallelism is 1
 *    while it can be parallelized in shuffle join.
 * 2. Broadcast join consumes more memory for redundant data in multiple BEs.
 * 3. The data size in each hash table of shuffle join is 1/parallelism of right table_size
 *    while it is full size in broadcast join. A small hash table can improve probe efficiency.
 * 4. The process of redistributing data by shuffle operation makes the tasks of each processing thread
 *    are more balanced, and it is more conducive to parallel computing.
 * Therefore, our join execution cost calculation model should consider parameters such as join execution mode,
 * parallelism, and left and right table size. The most important thing in the model is the evaluation of the
 * probe cost for each row. When the size of the right table is greater than bottom_number, the average probe
 * cost needs to be expanded by multiply a penalty factor. The parallel computing characteristics of shuffle
 * join can offset part of the probe cost by subtract a  parallel factor. We also set an upper limit on the
 * penalty factor to avoid cost distortion caused by huge table.
 */
public class HashJoinCostModel {

    private static final Logger LOG = LogManager.getLogger(HashJoinCostModel.class);
    private static final int BOTTOM_NUMBER = 100000;

    private static final double SHUFFLE_MAX_RATIO = 3;

    private static final double BROADCAST_MAT_RATIO = 12;

    private final Statistics leftStatistics;

    private final Statistics rightStatistics;

    private final ExpressionContext context;

    private final List<PhysicalPropertySet> inputProperties;

    private final List<BinaryPredicateOperator> eqOnPredicates;

    public HashJoinCostModel(ExpressionContext context, List<PhysicalPropertySet> inputProperties,
                             List<BinaryPredicateOperator> eqOnPredicates) {
        this.context = context;
        this.leftStatistics = context.getChildStatistics(0);
        this.rightStatistics = context.getChildStatistics(1);
        this.inputProperties = inputProperties;
        this.eqOnPredicates = eqOnPredicates;
    }

    public double getCpuCost() {
        JoinExecMode execMode = deriveJoinExecMode();
        double buildCost;
        double probeCost;
        double leftOutput = leftStatistics.getOutputSize(context.getChildOutputColumns(0));
        double rightOutput = rightStatistics.getOutputSize(context.getChildOutputColumns(1));
        int parallelFactor = Math.max(ConnectContext.get().getAliveBackendNumber(),
                ConnectContext.get().getSessionVariable().getDegreeOfParallelism());
        switch (execMode) {
            case BROADCAST:
                buildCost = rightOutput;
                probeCost = leftOutput * getAvgProbeCost();
                break;
            case SHUFFLE:
                buildCost = rightOutput / parallelFactor;
                probeCost = leftOutput * getAvgProbeCost();
                break;
            default:
                buildCost = rightOutput;
                probeCost = leftOutput;
        }
        return buildCost + probeCost;
    }

    public double getMemCost() {
        JoinExecMode execMode = deriveJoinExecMode();
        double rightOutput = rightStatistics.getOutputSize(context.getChildOutputColumns(1));
        double memCost;
        int beNum = Math.max(1, ConnectContext.get().getAliveBackendNumber());

        if (JoinExecMode.BROADCAST == execMode) {
            memCost = rightOutput * beNum;
        } else {
            memCost = rightOutput;
        }
        return memCost;
    }

    private double getAvgProbeCost() {
        JoinExecMode execMode = deriveJoinExecMode();
        double keySize = 0;
        for (BinaryPredicateOperator predicateOperator : eqOnPredicates) {
            ColumnRefOperator leftCol = (ColumnRefOperator) predicateOperator.getChild(0);
            ColumnRefOperator rightCol = (ColumnRefOperator) predicateOperator.getChild(1);
            if (context.getChildStatistics(1).getColumnStatistics().containsKey(leftCol)) {
                keySize += context.getChildStatistics(1).getColumnStatistic(leftCol).getAverageRowSize();
            } else if (context.getChildStatistics(1).getColumnStatistics().containsKey(rightCol)) {
                keySize += context.getChildStatistics(1).getColumnStatistic(rightCol).getAverageRowSize();
            }
        }
        double cachePenaltyFactor;
        int parallelFactor = Math.max(ConnectContext.get().getAliveBackendNumber(),
                ConnectContext.get().getSessionVariable().getDegreeOfParallelism()) * 2;
        double mapSize = Math.min(1, keySize) * rightStatistics.getOutputRowCount();

        if (JoinExecMode.BROADCAST == execMode) {
            cachePenaltyFactor = Math.max(1, Math.log(mapSize / BOTTOM_NUMBER));
            // normalize ration when it hits the limit
            cachePenaltyFactor = Math.min(BROADCAST_MAT_RATIO, cachePenaltyFactor);
        } else {
            cachePenaltyFactor = Math.max(1, (Math.log(mapSize / BOTTOM_NUMBER) -
                    Math.log(parallelFactor) / Math.log(2)));
            // normalize ration when it hits the limit
            cachePenaltyFactor = Math.min(SHUFFLE_MAX_RATIO, cachePenaltyFactor);
        }
        LOG.debug("execMode: {}, cachePenaltyFactor: {}", execMode, cachePenaltyFactor);
        return cachePenaltyFactor;
    }

    private JoinExecMode deriveJoinExecMode() {
        if (CollectionUtils.isEmpty(inputProperties)) {
            return JoinExecMode.EMPTY;
        } else if (inputProperties.get(1).getDistributionProperty().isBroadcast()) {
            return JoinExecMode.BROADCAST;
        } else {
            return JoinExecMode.SHUFFLE;
        }
    }

    private enum JoinExecMode {
        // no child input property info, use the original evaluation mode.
        EMPTY,

        // right child with broadcast info, use the broadcast join evaluation mode.
        BROADCAST,

        // right child without broadcast info, use the shuffle join evaluation mode.
        SHUFFLE
    }



}
