package org.broadinstitute.ddp.model.activity.types;

public enum CollationStyle {
    /**
    * Use the default collation style
    */
    DEFAULT,

    /**
    * The relative position of elements is defined the data source.
    * 
    * <p>For example, if a picklist question declares 3 options:
    *   [ "Option A", "Option C", "Option D" ]
    *   `IMPLICIT` guarantees that the options will always be ordered
    *   with the same relative positioning to one another.
    */
    IMPLICIT,

    /**
     * Elements are an ascending, natural sorted order.
     */
    NATURAL
}
