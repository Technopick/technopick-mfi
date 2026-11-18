package ke.co.technopick.mfi.core.accounting.application;

import java.util.Optional;
import java.util.UUID;
import ke.co.technopick.mfi.core.accounting.domain.JournalEntry;

/**
 * The accounting module's public posting port. Other modules post balanced entries through this
 * interface and never touch accounting persistence directly; the database still enforces balance,
 * open periods and immutability.
 */
public interface JournalPosting {

    void post(JournalEntry entry, String actor);

    Optional<JournalEntry> find(UUID entryId);
}
