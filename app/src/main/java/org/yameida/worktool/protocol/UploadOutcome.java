package org.yameida.worktool.protocol;

/** HTTP transport success alone is insufficient; the endpoint must explicitly accept the upload. */
public final class UploadOutcome {
    private UploadOutcome() {}
    public static boolean accepted(int httpStatus, Integer businessCode) {
        return httpStatus >= 200 && httpStatus < 300
                && businessCode != null && businessCode == 200;
    }
}
