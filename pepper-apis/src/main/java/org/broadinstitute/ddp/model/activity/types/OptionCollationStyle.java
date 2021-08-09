package org.broadinstitute.ddp.model.activity.types;

public enum OptionCollationStyle {
    /**
    * Use the server's default collation style
    */
    DEFAULT,

    /**
    * The relative elements is defined by their positioning in the study definition.
    * 
    * <p>For example, if a picklist question declares 3 options:
    *   [ "Option A", "Option C", "Option D" ]
    *   `IMPLICIT` guarantees that the options will always be ordered
    *   with the same relative positioning to one another.
    */
    IMPLICIT,

    /**
     * Elements are arranged using an ascending localized alphabetical sort.
     */
    ALPHABETICAL
}
