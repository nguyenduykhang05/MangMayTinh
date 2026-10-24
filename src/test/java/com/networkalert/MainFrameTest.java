package com.networkalert;

import com.networkalert.repository.ConfigRepository;
import com.networkalert.repository.IncidentRepository;
import com.networkalert.repository.TargetRepository;
import com.networkalert.service.AlertService;
import com.networkalert.service.MonitorService;
import com.networkalert.ui.MainFrame;
import com.networkalert.ui.theme.ThemeManager;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;

import static org.junit.jupiter.api.Assertions.*;

public class MainFrameTest {
    @Test
    public void testMainFrameCreation() throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        SwingUtilities.invokeAndWait(() -> {
            ThemeManager.setup();
            TargetRepository targetRepo = new TargetRepository();
            IncidentRepository incidentRepo = new IncidentRepository();
            ConfigRepository configRepo = new ConfigRepository();
            AlertService alertService = new AlertService(incidentRepo, configRepo);
            MonitorService monitorService = new MonitorService(alertService);

            MainFrame frame = new MainFrame(monitorService, alertService, targetRepo, incidentRepo);
            assertNotNull(frame);
            assertTrue(frame.getWidth() >= 1100);
            assertTrue(frame.getHeight() >= 680);
            frame.dispose();
            monitorService.shutdown();
        });
    }
}
