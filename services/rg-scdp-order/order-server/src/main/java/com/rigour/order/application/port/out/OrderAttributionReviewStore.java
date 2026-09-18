package com.rigour.order.application.port.out;

import com.rigour.order.api.v1.model.OrderAttributionReview.*;

/** 本域事务保存待复核方案，并以版本约束应用经审核的归属调整。 */
public interface OrderAttributionReviewStore {
    Context context(long id);

    Adjustment propose(long id, Propose command);

    Adjustment decide(long id, String reviewId, Review command);
}
