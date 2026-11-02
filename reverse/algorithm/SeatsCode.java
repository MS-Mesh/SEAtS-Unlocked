import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * SEAtS live-code generator: seed + counter -> MurmurHash2 x86_32 -> 5 digits -> Damm check digit.
 * Reference implementation for the algorithm recovered from SeatsMobile.dll.
 *
 * Test vectors (seed 832105):
 *   counter 0      -> 980580
 *   counter 1      -> 719256
 *   counter 12345  -> 183613
 *   counter 999999 -> 165845
 */
public final class SeatsCode {
	public static final int MURMUR_SEED = 0xC58F1A7A;
	public static final int DEFAULT_DURATION = 30;

	private static final int[][] DAMM = {
		{0, 3, 1, 7, 5, 9, 8, 6, 4, 2},
		{7, 0, 9, 2, 1, 5, 4, 8, 6, 3},
		{4, 2, 0, 6, 8, 7, 1, 3, 5, 9},
		{1, 7, 5, 0, 9, 8, 3, 4, 2, 6},
		{6, 1, 2, 3, 0, 4, 5, 9, 7, 8},
		{3, 6, 7, 4, 2, 0, 9, 5, 8, 1},
		{5, 8, 6, 9, 7, 2, 0, 1, 3, 4},
		{8, 9, 4, 5, 3, 6, 2, 0, 1, 7},
		{9, 4, 3, 8, 6, 1, 7, 2, 0, 5},
		{2, 5, 8, 1, 4, 3, 6, 7, 9, 0}
	};

	private SeatsCode() {
	}

	/** MurmurHash2 x86_32, matching the official .NET implementation. */
	public static int murmurHash2(byte[] data, int seed) {
		final int multiplier = 0x5BD1E995;
		final int shift = 24;
		int hash = seed ^ data.length;
		int offset = 0;
		int remaining = data.length;
		while (remaining >= 4) {
			int mixed = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8) | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
			mixed *= multiplier;
			mixed ^= mixed >>> shift;
			mixed *= multiplier;
			hash *= multiplier;
			hash ^= mixed;
			offset += 4;
			remaining -= 4;
		}
		switch (remaining) {
			case 3:
				hash ^= (data[offset + 2] & 0xFF) << 16;
				// fall through
			case 2:
				hash ^= (data[offset + 1] & 0xFF) << 8;
				// fall through
			case 1:
				hash ^= data[offset] & 0xFF;
				hash *= multiplier;
				break;
			default:
				break;
		}
		hash ^= hash >>> 13;
		hash *= multiplier;
		hash ^= hash >>> 15;
		return hash;
	}

	public static int dammDigit(String digits) {
		int interim = 0;
		for (int index = 0; index < digits.length(); index++) interim = DAMM[interim][digits.charAt(index) - '0'];
		return interim;
	}

	public static boolean dammValid(String code) {
		return code != null && !code.isEmpty() && dammDigit(code) == 0;
	}

	public static String getTotp(String seed, long counter) {
		long value = Long.parseLong(seed) + counter;
		byte[] bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
		int hash = murmurHash2(bytes, MURMUR_SEED);
		String head = String.format(Locale.US, "%05d", (hash & 0xFFFFFFFFL) % 100000);
		return head + dammDigit(head);
	}

	public static String generateCode(String seed, long startCutoff, long now, int durationSeconds) {
		int duration = durationSeconds > 0 ? durationSeconds : DEFAULT_DURATION;
		return getTotp(seed, Math.floorDiv(now - startCutoff, duration));
	}

	public static String qrUrl(String code) {
		return "https://seatssoftware.com/qr/" + code;
	}

	public static void main(String[] args) {
		if (!"980580".equals(getTotp("832105", 0))) throw new AssertionError();
		if (!"719256".equals(getTotp("832105", 1))) throw new AssertionError();
		if (!"183613".equals(getTotp("832105", 12345))) throw new AssertionError();
		if (!"165845".equals(getTotp("832105", 999999))) throw new AssertionError();
		if (!dammValid("855824")) throw new AssertionError();
		System.out.println("all test vectors passed");
	}
}