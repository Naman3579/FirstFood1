package com.firstfood.common.ratelimit;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Works out the real client address WITHOUT trusting {@code X-Forwarded-For} from arbitrary callers.
 *
 * Rule: the TCP peer is the only address that cannot be forged. {@code X-Forwarded-For} is consulted only if
 * that peer is itself a configured trusted proxy, and then it is read RIGHT TO LEFT, skipping trusted proxy
 * hops; the first address that is not a trusted proxy is the client. Everything to the left of it is
 * attacker-controllable and is ignored. With no trusted proxies configured (the default) the header is never
 * read at all.
 *
 * Inputs are only ever parsed as IP LITERALS - a hostname in the header can never trigger a DNS lookup.
 */
public final class ClientIpResolver {

    public static final String UNKNOWN = "unknown";

    private static final Pattern IPV4 = Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final List<Cidr> trusted;

    public ClientIpResolver(List<String> trustedProxyCidrs) {
        List<Cidr> parsed = new ArrayList<>();
        if (trustedProxyCidrs != null) {
            for (String cidr : trustedProxyCidrs) {
                if (cidr != null && !cidr.isBlank()) {
                    parsed.add(Cidr.parse(cidr.trim()));
                }
            }
        }
        this.trusted = List.copyOf(parsed);
    }

    /**
     * @param remoteAddr         the socket peer ({@code request.getRemoteAddr()})
     * @param forwardedForValues every {@code X-Forwarded-For} header value, in order (may be null/empty)
     */
    public String resolve(String remoteAddr, List<String> forwardedForValues) {
        InetAddress peer = parseLiteral(remoteAddr);
        if (peer == null) {
            return UNKNOWN;
        }
        if (trusted.isEmpty() || !isTrusted(peer) || forwardedForValues == null || forwardedForValues.isEmpty()) {
            return format(peer);
        }

        List<String> hops = new ArrayList<>();
        for (String headerValue : forwardedForValues) {
            if (headerValue == null) {
                continue;
            }
            for (String part : headerValue.split(",")) {
                hops.add(part.trim());
            }
        }

        InetAddress current = peer;
        for (int i = hops.size() - 1; i >= 0; i--) {
            InetAddress hop = parseLiteral(stripPort(hops.get(i)));
            if (hop == null) {
                // A garbage entry can only have been written by the client or a broken proxy. Do not guess:
                // attribute the request to the nearest hop we do know about.
                return format(current);
            }
            if (!isTrusted(hop)) {
                return format(hop);
            }
            current = hop;
        }
        return format(current);
    }

    /**
     * The key IPv6 clients are limited by: the /64 prefix. One household or host is routinely handed a whole /64,
     * so limiting single /128 addresses would let an attacker rotate through 2^64 of them.
     */
    public static String bucket(String ip) {
        if (ip == null || !ip.contains(":")) {
            return ip == null ? UNKNOWN : ip;
        }
        InetAddress parsed = parseLiteral(ip);
        if (parsed == null || parsed.getAddress().length != 16) {
            return ip;
        }
        byte[] b = parsed.getAddress();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i += 2) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(Integer.toHexString(((b[i] & 0xff) << 8) | (b[i + 1] & 0xff)));
        }
        return sb.append("::/64").toString();
    }

    private boolean isTrusted(InetAddress address) {
        for (Cidr cidr : trusted) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    private static String format(InetAddress address) {
        return address.getHostAddress();
    }

    private static String stripPort(String value) {
        if (value.startsWith("[")) { // [v6]:port
            int close = value.indexOf(']');
            return close > 0 ? value.substring(1, close) : value;
        }
        int firstColon = value.indexOf(':');
        if (firstColon > 0 && firstColon == value.lastIndexOf(':') && value.indexOf('.') > 0) {
            return value.substring(0, firstColon); // 1.2.3.4:5678
        }
        return value;
    }

    /** Parses an IP literal only. Returns null for anything else (never resolves names). */
    static InetAddress parseLiteral(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        try {
            var m = IPV4.matcher(v);
            if (m.matches()) {
                for (int i = 1; i <= 4; i++) {
                    if (Integer.parseInt(m.group(i)) > 255) {
                        return null;
                    }
                }
                return InetAddress.getByName(v); // literal: no DNS
            }
            if (v.contains(":") && IPV6_CHARS.matcher(v).matches()) {
                return InetAddress.getByName(v); // literal (colons only occur in IPv6): no DNS
            }
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    /** One trusted-proxy range, e.g. {@code 10.0.0.0/8}, {@code fd00::/8} or a single address. */
    static final class Cidr {
        private final byte[] network;
        private final int prefixBits;

        private Cidr(byte[] network, int prefixBits) {
            this.network = network;
            this.prefixBits = prefixBits;
        }

        static Cidr parse(String text) {
            String addressPart = text;
            Integer prefix = null;
            int slash = text.indexOf('/');
            if (slash >= 0) {
                addressPart = text.substring(0, slash);
                try {
                    prefix = Integer.parseInt(text.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + text);
                }
            }
            InetAddress address = parseLiteral(addressPart);
            if (address == null) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR (not an IP literal): " + text);
            }
            int max = address.getAddress().length * 8;
            int bits = prefix == null ? max : prefix;
            if (bits < 0 || bits > max) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR prefix: " + text);
            }
            return new Cidr(address.getAddress(), bits);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false; // an IPv4 range never matches an IPv6 address and vice versa
            }
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainder = prefixBits % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = (0xff << (8 - remainder)) & 0xff;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
