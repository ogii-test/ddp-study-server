package org.broadinstitute.ddp.model.activity.types;

public enum CollationPolicy {
    /**
    * Use the default collation policy
    */
    DEFAULT,

    /**
    * The relative position of elements is defined by the data source.
    * 
    * <p>For example, given an array of 3 elements:
    *   [ "Option A", "Option C", "Option D" ]
    *   `IMPLICIT` guarantees that the elements will always be ordered
    *   with the same relative positioning to one another.
    */
    IMPLICIT,

    /**
     * Elements should be sorted in an ascending, natural order.
     */
    NATURAL
}
