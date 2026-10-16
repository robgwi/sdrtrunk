package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.audio.broadcast.openmhz.OpenMHzConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BroadcastConfigurationCloneTest
{
    @Test
    void broadcastifyCallCloneKeepsConnectionSettingsAndStartsDisabled()
    {
        BroadcastifyCallConfiguration source = new BroadcastifyCallConfiguration();
        source.setName("County Calls");
        source.setHost("https://example.test/calls");
        source.setSystemID(42);
        source.setApiKey("0123456789abcdef");
        source.setMaximumRecordingAge(123_000);
        source.setTestEnabled(true);
        source.setTestInterval(30);
        source.setEnabled(true);

        BroadcastifyCallConfiguration copy = (BroadcastifyCallConfiguration)source.copyOfDisabled();

        assertEquals(source.getName(), copy.getName());
        assertEquals(source.getHost(), copy.getHost());
        assertEquals(source.getSystemID(), copy.getSystemID());
        assertEquals(source.getApiKey(), copy.getApiKey());
        assertEquals(source.getMaximumRecordingAge(), copy.getMaximumRecordingAge());
        assertEquals(source.getTestInterval(), copy.getTestInterval());
        assertFalse(copy.isEnabled());
    }

    @Test
    void openMHzCloneKeepsConnectionSettingsAndStartsDisabled()
    {
        OpenMHzConfiguration source = new OpenMHzConfiguration();
        source.setName("Metro");
        source.setHost("https://api.example.test");
        source.setSystemName("metro-system");
        source.setApiKey("long-api-key");
        source.setMaximumRecordingAge(456_000);
        source.setEnabled(true);

        OpenMHzConfiguration copy = (OpenMHzConfiguration)source.copyOfDisabled();

        assertEquals(source.getName(), copy.getName());
        assertEquals(source.getHost(), copy.getHost());
        assertEquals(source.getSystemName(), copy.getSystemName());
        assertEquals(source.getApiKey(), copy.getApiKey());
        assertEquals(source.getMaximumRecordingAge(), copy.getMaximumRecordingAge());
        assertFalse(copy.isEnabled());
    }
}
