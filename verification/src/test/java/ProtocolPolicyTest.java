import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class ProtocolPolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void protocolAndExportRegressionChecks() throws Exception {
        ProtocolSafetyTest.main(new String[]{temporary.getRoot().getAbsolutePath()});
    }
}
