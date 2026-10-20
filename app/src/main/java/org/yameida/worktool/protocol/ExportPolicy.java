package org.yameida.worktool.protocol;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Only operator-reviewed, redacted text explicitly staged under files/exports is exportable. */
public final class ExportPolicy {
    public static final int MAX_BYTES = 1024 * 1024;
    private ExportPolicy() {}

    public static byte[] readReviewedFile(File exportRoot, String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,79}\\.(txt|json)"))
            throw new IOException("Use a reviewed .txt/.json export filename, not a path");
        File root = exportRoot.getCanonicalFile();
        File file = new File(root, name).getCanonicalFile();
        if (!root.equals(file.getParentFile()) || !file.isFile())
            throw new IOException("Export is not an allowed regular file");
        if (file.length() == 0 || file.length() > MAX_BYTES)
            throw new IOException("Export must be between 1 byte and 1 MiB");
        // Bound the actual read as well: a concurrently growing file cannot exhaust memory.
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_BYTES) throw new IOException("Export exceeds 1 MiB");
                output.write(buffer, 0, count);
            }
            if (output.size() == 0) throw new IOException("Export is empty");
            return output.toByteArray();
        }
    }
}
