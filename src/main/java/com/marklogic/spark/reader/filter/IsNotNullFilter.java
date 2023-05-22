package com.marklogic.spark.reader.filter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.client.expression.PlanBuilder;
import org.apache.spark.sql.sources.IsNotNull;

class IsNotNullFilter implements OpticFilter {

    final static long serialVersionUID = 1;

    private IsNotNull filter;

    IsNotNullFilter(IsNotNull filter) {
        this.filter = filter;
    }

    @Override
    public void populateArg(ObjectNode arg) {
        arg
            .put("ns", "op").put("fn", "is-defined")
            .putArray("args").addObject()
            .put("ns", "op").put("fn", "col")
            .putArray("args").add(filter.attribute());
    }

    @Override
    public PlanBuilder.Plan bindFilterValue(PlanBuilder.Plan plan) {
        return plan;
    }
}
