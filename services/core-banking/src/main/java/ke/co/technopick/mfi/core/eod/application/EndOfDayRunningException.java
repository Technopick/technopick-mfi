package ke.co.technopick.mfi.core.eod.application;

import ke.co.technopick.mfi.core.common.ConflictException;

/** Another process is running end of day for this tenant right now. */
public class EndOfDayRunningException extends ConflictException {

    private static final long serialVersionUID = 1L;

    public EndOfDayRunningException(String message) {
        super(message);
    }
}
