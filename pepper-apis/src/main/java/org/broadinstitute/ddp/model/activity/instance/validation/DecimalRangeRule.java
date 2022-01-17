package org.broadinstitute.ddp.model.activity.instance.validation;

import com.google.gson.annotations.SerializedName;
import org.broadinstitute.ddp.model.activity.instance.answer.NumericAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.NumericDecimalAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.NumericIntegerAnswer;
import org.broadinstitute.ddp.model.activity.instance.question.Question;
import org.broadinstitute.ddp.model.activity.types.NumericType;
import org.broadinstitute.ddp.model.activity.types.RuleType;

import java.math.BigDecimal;

/**
 * A validation rule that checks numeric integer value is within an optional min/max range, inclusive.
 */
public class DecimalRangeRule extends Rule<NumericAnswer> {

    @SerializedName("min")
    private BigDecimal min;

    @SerializedName("max")
    private BigDecimal max;

    public static DecimalRangeRule of(Long id, String message, String hint, boolean allowSave, BigDecimal min, BigDecimal max) {
        DecimalRangeRule rule = DecimalRangeRule.of(message, hint, allowSave, min, max);
        rule.setId(id);
        return rule;
    }

    public static DecimalRangeRule of(String message, String hint, boolean allowSave, BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException("Must provide a valid range");
        }
        return new DecimalRangeRule(message, hint, allowSave, min, max);
    }

    private DecimalRangeRule(String message, String hint, boolean allowSave, BigDecimal min, BigDecimal max) {
        super(RuleType.INT_RANGE, message, hint, allowSave);
        this.min = min;
        this.max = max;
    }

    @Override
    public boolean validate(Question<NumericAnswer> question, NumericAnswer answer) {
        if (answer == null) {
            return false;
        } else if (answer.getValue() == null) {
            return true;
        } else if (answer.getNumericType() == NumericType.DECIMAL) {
            BigDecimal value = ((NumericDecimalAnswer) answer).getValue();
            return (min == null || min.compareTo(value) <= 0) && (max == null || value.compareTo(max) <= 0);
        } else {
            return false;
        }
    }

    public BigDecimal getMin() {
        return min;
    }

    public BigDecimal getMax() {
        return max;
    }
}
