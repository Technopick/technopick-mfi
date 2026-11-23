package ke.co.technopick.mfi.core.eod.application;

import ke.co.technopick.mfi.core.common.ConflictException;

/** The day cannot close yet for a business reason people must resolve (open tills, no open period). */
public class EndOfDayBlockedException extends ConflictException {

    private static final long serialVersionUID = 1L;

    public EndOfDayBlockedException(String message) {
        super(message);
    }
}
