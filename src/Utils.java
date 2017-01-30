/* #### Utilities ####
 * This class contains various utility methods *
 */
package net.floodlightcontroller.splus;

import javax.crypto.Mac;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.BadPaddingException;
import java.security.*;

public class Utils {
	/* Generates a random integer between a specified range */
	public static int randInt(int min, int max) {
		// get random number generator
		Random rand = new Random();

		// generate random value between given limits
		return rand.nextInt((max - min) + 1) + min;
	};

    /* Generates Hash Message Authentication Code Digest
     * Reference: http://www.supermind.org/blog/1102/generating-hmac-md5-sha1-sha256-etc-in-java
	 */
	public static String hmacDigest(String msg, String keyString) {
		if (Constants.DEBUG) {
			System.out.println("HMAC Digest: Msg => '" + msg + "',  IntegrityKey => '" + keyString + "'");
		};

		// HMAC SHA1 algorithm
		String algorithm = "HmacSHA1"; 
		String digest = null;

		try {
			SecretKeySpec key = new SecretKeySpec(keyString.getBytes("UTF-8"), algorithm);
			Mac mac = Mac.getInstance(algorithm);
			mac.init(key);

			// ISO-8859-1
			byte[] bytes = mac.doFinal(msg.getBytes("ISO-8859-1")); 
			StringBuffer hash = new StringBuffer();

			for (int i = 0; i < bytes.length; i++) {
				String hex = Integer.toHexString(0xFF & bytes[i]);
				if (hex.length() == 1) { hash.append('0'); }
				hash.append(hex);
			};

			digest = hash.toString();

		} catch (UnsupportedEncodingException e) {
			System.out.println(e.getMessage());
		} catch (InvalidKeyException e) {
			System.out.println(e.getMessage());
		} catch (NoSuchAlgorithmException e) {
			System.out.println(e.getMessage());
		} finally {
			return digest;
		};
	};

	/* 128 bit AES encryption algorithm */
	public static byte[] aesEncrypt(
		String pText, 		// Plain Text to encrypt
		String secretKey	// Encryption key
	) {
		if (Constants.DEBUG) {
			System.out.println("aesEncrypt: plainText => '" + pText + "', EncryptionKey => '" + secretKey + "'");
		}

		byte[] ptextBytes = null;
		try {
			ptextBytes = pText.getBytes("ISO-8859-1");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		byte[] encBytes = null;
   
		try {
			String ivString = "0123456789abcdef";
			byte[] secret = secretKey.getBytes();
			byte[] iv = ivString.getBytes();
			Cipher c = Cipher.getInstance("AES/CBC/PKCS5PADDING");
			Key k = new SecretKeySpec(secret, "AES");

			c.init(Cipher.ENCRYPT_MODE, k, new IvParameterSpec(iv));
			encBytes = c.doFinal(ptextBytes);

		} catch (IllegalBlockSizeException ex) {
			System.out.println(ex.getMessage());
		} catch (BadPaddingException ex) {
			System.out.println(ex.getMessage());
		} catch (InvalidKeyException ex) {
			System.out.println(ex.getMessage());
		} catch (NoSuchAlgorithmException ex) {
			System.out.println(ex.getMessage());
		} catch (NoSuchPaddingException ex) {
			System.out.println(ex.getMessage());
		} catch (InvalidAlgorithmParameterException ex) {
			System.out.println(ex.getMessage());
		} finally {
			return encBytes;
		};
	};

	/* 128 bit AES decryption algorithm */
	public static String aesDecrypt(
		byte[] encBytes,	// Cipher text byte array
		String secretKey	// Encryption key
	) {
		if (Constants.DEBUG) {
            try {
				System.out.println("aesDecrypt: cipherText => '"
								+ new String(encBytes, "ISO-8859-1")
								+ "', DecryptionKey => '" + secretKey + "'");
			} catch (UnsupportedEncodingException e) {
				e.printStackTrace();
			};
		};

		String pText = new String();

		try {
			String ivString = "0123456789abcdef";
			byte[] secret = secretKey.getBytes();
			byte[] iv = ivString.getBytes();
			Cipher c = Cipher.getInstance("AES/CBC/PKCS5PADDING");
			Key k = new SecretKeySpec(secret, "AES");

			c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(iv));
			byte[] decBytes = c.doFinal(encBytes);
			pText = new String(decBytes);

		} catch (IllegalBlockSizeException ex) {
			System.out.println(ex.getMessage());
		} catch (BadPaddingException ex) {
			System.out.println(ex.getMessage());
		} catch (InvalidKeyException ex) {
			System.out.println(ex.getMessage());
		} catch (NoSuchAlgorithmException ex) {
			System.out.println(ex.getMessage());
		} catch (NoSuchPaddingException ex) {
			System.out.println(ex.getMessage());
		} catch (InvalidAlgorithmParameterException ex) {
			System.out.println(ex.getMessage());
		} finally {
			return pText;
		};
	};
}

