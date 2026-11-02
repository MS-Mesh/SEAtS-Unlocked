package com.msmesh.seats.code;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decrypts the tenant settings that SEAtS sends encrypted.
 * MobilePhone is the important one: its plaintext is the HMAC key material for the check-in fingerprint.
 */
public final class SettingCrypto {
	private static final byte[] KEY = hex("F137204FFC37AC4D625E8913F771C5A6");
	private static final byte[] IV = hex("005C91EF5AE3173B37BE55D4EA490C92");

	private SettingCrypto() {
	}

	public static String decrypt(String base64) {
		if (base64 == null || base64.isEmpty()) return "";
		try {
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new IvParameterSpec(IV));
			return new String(cipher.doFinal(Base64.decode(base64, Base64.DEFAULT)), StandardCharsets.UTF_8).trim();
		} catch (Exception error) {
			return "";
		}
	}

	private static byte[] hex(String value) {
		byte[] decoded = new byte[value.length() / 2];
		for (int index = 0; index < decoded.length; index++) decoded[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
		return decoded;
	}
}