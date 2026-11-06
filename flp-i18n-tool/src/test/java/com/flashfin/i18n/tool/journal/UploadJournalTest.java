package com.flashfin.i18n.tool.journal;

import com.flashfin.i18n.tool.sync.UploadEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UploadJournalTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void persistsCompletedEntriesForResume() throws Exception {
        Path data = temp.newFolder("data").toPath();
        String planId = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        UploadEntry entry = new UploadEntry("demo", "flp-code", "code_1", "code", "en", "One");

        UploadJournal journal = UploadJournal.open(data, planId);
        journal.markCompleted(entry);
        assertTrue(Files.isRegularFile(journal.getFile()));

        UploadJournal reloaded = UploadJournal.open(data, planId);
        assertTrue(reloaded.isCompleted(entry));
        assertEquals(1, reloaded.completedEntries().size());
        assertEquals(entry.getValue(), reloaded.completedEntries().get(0).getValue());
    }

    @Test
    public void unknownPlanStartsEmpty() throws Exception {
        Path data = temp.newFolder("data").toPath();
        UploadJournal journal = UploadJournal.open(data,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        assertEquals(0, journal.completedEntries().size());
    }
}
