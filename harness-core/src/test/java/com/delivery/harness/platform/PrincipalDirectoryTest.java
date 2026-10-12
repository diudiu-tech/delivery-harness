package com.delivery.harness.platform;
import com.delivery.harness.platform.auth.PrincipalDirectory;
import com.delivery.harness.platform.store.PlatformException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PrincipalDirectoryTest {
    private final String claims="""
            [{"tenant_id":"t","user_id":"reviewer","roles":["REVIEWER"],"city_codes":["C"],"station_ids":["S"]},
             {"tenant_id":"other","user_id":"other-reviewer","roles":["REVIEWER"],"city_codes":["*"],"station_ids":["*"]},
             {"tenant_id":"t","user_id":"agent","roles":["AGENT"],"city_codes":["*"],"station_ids":["*"]}]
            """;
    @Test void assignmentRequiresKnownSameTenantReviewerWithMatchingScope(){var directory=new PrincipalDirectory(claims,"pilot");assertEquals("reviewer",directory.reviewer("t","reviewer","C","S").userId());assertThrows(PlatformException.class,() -> directory.reviewer("t","reviewer","OTHER","S"));assertThrows(PlatformException.class,() -> directory.reviewer("t","other-reviewer","C","S"));assertThrows(PlatformException.class,() -> directory.reviewer("t","agent","C","S"));assertEquals(1,directory.reviewers("t","C","S").size());}
    @Test void demoIdentitiesAreNeverAcceptedIntoPilotDirectory(){assertFalse(new PrincipalDirectory("[]","demo").reviewers("demo","C","S").isEmpty());assertTrue(new PrincipalDirectory("[]","pilot").reviewers("demo","C","S").isEmpty());}
    @Test void colonSeparatedTenantAndUserNamesCannotCollide(){var directory=new PrincipalDirectory("[{\"tenant_id\":\"t:a\",\"user_id\":\"b\",\"roles\":[\"REVIEWER\"],\"city_codes\":[\"*\"],\"station_ids\":[\"*\"]}]","pilot");assertThrows(PlatformException.class,() -> directory.reviewer("t","a:b","C","S"));}
}
