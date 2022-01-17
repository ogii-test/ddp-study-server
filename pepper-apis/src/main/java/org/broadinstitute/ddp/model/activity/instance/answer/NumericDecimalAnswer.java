package org.broadinstitute.ddp.model.activity.instance.answer;

import com.google.gson.annotations.SerializedName;
import org.broadinstitute.ddp.model.activity.types.NumericType;

import java.math.BigDecimal;

/**
 * A numeric answer that only supports integers, i.e. whole numbers. Represented using a `long` to cover a wider range of integers.
 */
public class NumericDecimalAnswer extends NumericAnswer<BigDecimal> {

    @SerializedName("value")
    private BigDecimal value;

    public NumericDecimalAnswer(Long answerId, String questionStableId, String answerGuid, BigDecimal value) {
        super(answerId, questionStableId, answerGuid, NumericType.DECIMAL);
        this.value = value;
    }

    public NumericDecimalAnswer(Long answerId, String questionStableId, String answerGuid, BigDecimal value, String actInstanceGuid) {
        super(answerId, questionStableId, answerGuid, NumericType.DECIMAL, actInstanceGuid);
        this.value = value;
    }

    @Override
    public BigDecimal getValue() {
        return value;
    }

    @Override
    public void setValue(BigDecimal value) {
        this.value = value;
    }

    @Override
    public boolean isEmpty() {
        return value == null;
    }
}
