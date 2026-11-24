package ke.co.technopick.mfi.integration.daraja;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Callback authentication (design section 7): a callback is trusted only if it comes from an
 * allowed source range AND names a configured secret path token. The tenant is derived from the
 * token, never from the payload; the payload's short code must then match that token's paybill.
 */
@Component
public class CallbackGuard {

    private final DarajaProperties props;

    public CallbackGuard(DarajaProperties props) {
        this.props = props;
    }

    public boolean sourceAllowed(String remoteAddress) {
        try {
            InetAddress address = InetAddress.getByName(remoteAddress);
            for (String cidr : props.allowedCidrs()) {
                if (inRange(address, cidr)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            return false;
        }
        return false;
    }

    /** Constant-time comparison against every configured token. */
    public Optional<DarajaProperties.Paybill> paybillFor(String token) {
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        DarajaProperties.Paybill match = null;
        for (DarajaProperties.Paybill p : props.paybills()) {
            if (MessageDigest.isEqual(presented, p.token().getBytes(StandardCharsets.UTF_8))) {
                match = p;
            }
        }
        return Optional.ofNullable(match);
    }

    /** Constant-time comparison against every configured B2C callback token. */
    public Optional<DarajaProperties.B2cAccount> b2cAccountFor(String token) {
        byte[] presented = token.getBytes(StandardCharsets.UTF_8);
        DarajaProperties.B2cAccount match = null;
        for (DarajaProperties.B2cAccount a : props.b2cAccounts()) {
            if (MessageDigest.isEqual(presented, a.token().getBytes(StandardCharsets.UTF_8))) {
                match = a;
            }
        }
        return Optional.ofNullable(match);
    }

    public static boolean inRange(InetAddress address, String cidr) throws UnknownHostException {
        String[] parts = cidr.split("/");
        InetAddress network = InetAddress.getByName(parts[0]);
        byte[] a = address.getAddress();
        byte[] n = network.getAddress();
        if (a.length != n.length) {
            return false;
        }
        int bits = parts.length > 1 ? Integer.parseInt(parts[1]) : a.length * 8;
        BigInteger mask = BigInteger.ONE.shiftLeft(a.length * 8).subtract(BigInteger.ONE)
                .shiftRight(bits).not().and(BigInteger.ONE.shiftLeft(a.length * 8).subtract(BigInteger.ONE));
        return new BigInteger(1, a).and(mask).equals(new BigInteger(1, n).and(mask));
    }

    public List<String> ranges() {
        return props.allowedCidrs();
    }
}
