package de.tum.in.securebitcoinwallet.javacardapplet;

import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.Util;
import javacard.security.AESKey;
import javacard.security.CryptoException;
import javacard.security.ECPrivateKey;
import javacard.security.ECPublicKey;
import javacard.security.KeyBuilder;
import javacard.security.KeyPair;
import javacard.security.MessageDigest;
import javacard.security.RandomData;
import javacard.security.Signature;
import javacardx.crypto.Cipher;

/**
 * Storage for private keys. Uses two arrays to store the private key and a
 * mapping array with addresses. The mapping array is used to easily find the
 * private key for a given address.
 * 
 * @author Benedikt Schlagberger
 */
public class KeyStore {

	/**
	 * The encryption to use for encrypting the private keys.
	 */
	private static final byte ENCRYPTION_KEY_TYPE = KeyBuilder.TYPE_AES;

	/**
	 * AES encryption used for encrypting the private key for export.
	 */
	private static final byte ENCRYPTION_MODE = Cipher.ALG_AES_BLOCK_128_CBC_NOPAD;

	/**
	 * Length of the AES key.
	 */
	private static final short ENCRYPTION_KEY_LENGTH = KeyBuilder.LENGTH_AES_128;

	/**
	 * The maximum length of a private key in bytes.
	 */
	private static final short PRIVATE_KEY_SIZE = 32;

	/**
	 * The store for the private keys. If a key is null, the slot is free and
	 * can be used for a new key. This should be kept in sync with
	 * {@link #addressToKeyIndexMap}.
	 */
	private EncryptedPrivateKey[] keys;

	/**
	 * Map to find the key for a specified address. Maps addresses to the index
	 * of the corresponding private key inside the {@link keys} array.
	 */
	private BitcoinAddress[] addressToKeyIndexMap;

	/**
	 * The current number of registered keys.
	 */
	private short numberOfKeys;

	/**
	 * Index of the key with has been selected for signing data.
	 */
	private short selectedAddress;

	/**
	 * Key used for encryption of the private keys. Generated randomly during
	 * instantiation of this KeyStore.
	 */
	private AESKey aesKey;

	/**
	 * Cipher used for AES encryption of the private keys.
	 */
	private Cipher aesCipher;

	/**
	 * Index of the key inside the {@link keys} array.
	 */
	private byte addressIndex;

	/**
	 * Used to generate a new keypair and sign transactions.
	 */
	private KeyPair keyPair;

	/**
	 * Signature used to sign messages.
	 */
	private Signature signature;

	/**
	 * Digest used for hashing with SHA256.
	 */
	private MessageDigest sha256Digest;

	/**
	 * Buffer used to encrypt private keys and temporary store key data.
	 */
	private byte[] keyBuffer;

	/**
	 * Buffer used in {@link #encryptPrivateKey(ECPrivateKey, byte[], short)}
	 * and {@link #decryptPrivateKey(EncryptedPrivateKey, byte[], short)}.
	 */
	private byte[] encryptionBuffer;

	/**
	 * Buffer for RIPEMD160 hashing and base58 encoding.
	 */
	private byte[] hashBuffer;

	/**
	 * Constructor. Has to be called inside the constructor of the applet to
	 * reserve needed memory.
	 * 
	 * @param storeSize The size of the keystore in keys. Maximum is 254 (0xFE).
	 *            If a storeSize bigger than 254 is provided, the size will be
	 *            set to 254.
	 * @param addressSize The maximum size of an address in bytes. Maximum is
	 *            254. If the maximum is exceeded, the maximum of 254 is used
	 *            instead.
	 */
	public KeyStore(short storeSize, short addressSize) {
		addressIndex = 0;

		if (storeSize >= 0xFF) {
			ISOException.throwIt(StatusCodes.WRONG_LENGTH);
		}

		if (addressSize >= 0xFF) {
			ISOException.throwIt(StatusCodes.WRONG_LENGTH);
		}

		keyBuffer = new byte[256];
		encryptionBuffer = new byte[64];
		hashBuffer = new byte[256];

		RandomData.getInstance(RandomData.ALG_SECURE_RANDOM).generateData(
				keyBuffer, (short) 0, (short) (ENCRYPTION_KEY_LENGTH / 8));

		aesKey = (AESKey) KeyBuilder.buildKey(ENCRYPTION_KEY_TYPE,
				ENCRYPTION_KEY_LENGTH, false);
		aesKey.setKey(keyBuffer, (short) 0);

		aesCipher = Cipher.getInstance(ENCRYPTION_MODE, false);

		keyPair = new KeyPair(KeyPair.ALG_EC_FP, KeyBuilder.LENGTH_EC_FP_256);

		// Set EC params
		ECPrivateKey privKey = (ECPrivateKey) keyPair.getPrivate();

		privKey.setFieldFP(SECP256K1.P, (short) 0, (short) SECP256K1.P.length);
		privKey.setA(SECP256K1.a, (short) 0, (short) SECP256K1.a.length);
		privKey.setB(SECP256K1.b, (short) 0, (short) SECP256K1.b.length);
		privKey.setG(SECP256K1.G, (short) 0, (short) SECP256K1.G.length);
		privKey.setR(SECP256K1.R, (short) 0, (short) SECP256K1.R.length);
		privKey.setK(SECP256K1.K);

		ECPublicKey pubKey = (ECPublicKey) keyPair.getPublic();

		pubKey.setFieldFP(SECP256K1.P, (short) 0, (short) SECP256K1.P.length);
		pubKey.setA(SECP256K1.a, (short) 0, (short) SECP256K1.a.length);
		pubKey.setB(SECP256K1.b, (short) 0, (short) SECP256K1.b.length);
		pubKey.setG(SECP256K1.G, (short) 0, (short) SECP256K1.G.length);
		pubKey.setR(SECP256K1.R, (short) 0, (short) SECP256K1.R.length);
		pubKey.setK(SECP256K1.K);

		keys = new EncryptedPrivateKey[storeSize];

		sha256Digest = MessageDigest.getInstance(MessageDigest.ALG_SHA_256,
				false);

		addressToKeyIndexMap = new BitcoinAddress[storeSize];

		// Allocate memory for addresses
		for (short i = 0; i < addressToKeyIndexMap.length; i++) {
			addressToKeyIndexMap[i] = new BitcoinAddress(addressSize);
		}

		// Allocate memory for keys
		for (short i = 0; i < keys.length; i++) {
			keys[i] = new EncryptedPrivateKey(PRIVATE_KEY_SIZE);
		}
	}

	/**
	 * Selects the key specified by the given Bitcoin address for signing data.
	 * 
	 * @param src The buffer, in which the addresscan be found
	 * @param addrOff The offset of the Bitcoin address inside the buffer
	 * @param addrLength The length of the Bitcoin address inside the buffer
	 */
	public void selectKeyForSignature(byte[] src, short addrOff,
			short addrLength) {

		calculateIndexForAddress(src, addrOff, addrLength);

		selectedAddress = addressIndex;

		if (addressIndex == 0xFF) {
			ISOException.throwIt(StatusCodes.KEY_NOT_FOUND);
		}
	}

	/**
	 * Signs the given sha256Hash with the key of the previously selected
	 * private key. Input
	 * and output buffer may overlap.
	 * 
	 * @param src The buffer, in which the sha256Hash can be found
	 * @param msgOff The offset of the sha256Hash inside the buffer
	 * @param msgLength The length of the sha256Hash inside the buffer
	 * @param dest The buffer in which the signed message will be written
	 * @param destOff The offset inside the output buffer
	 * 
	 * @return Length of the signed message inside the output buffer
	 */
	public short signMessage(byte[] src, short msgOff, short msgLength,
			byte[] dest, short destOff) {

		if (selectedAddress == (short) 0xFF) {
			ISOException.throwIt(StatusCodes.NO_KEY_SELECTED);
		}

		short keyLength = decryptPrivateKey(keys[selectedAddress], keyBuffer,
				(short) 0);

		ECPrivateKey privateKey = (ECPrivateKey) keyPair.getPrivate();
		privateKey.setS(keyBuffer, (short) 0, keyLength);

		signature = Signature.getInstance(Signature.ALG_ECDSA_SHA_256, false);
		signature.init(privateKey, Signature.MODE_SIGN);

		return signature.sign(src, msgOff, msgLength, dest, destOff);
	}

	/**
	 * Generates a new key pair and returns the public key if the store has
	 * space left. Stores the private key in this {@link KeyStore}.
	 * 
	 * @param dest The output buffer where the new public key will bew
	 *            written.
	 * @param destOff The offset inside the output buffer.
	 * 
	 * @return The length of the new public key in bytes.
	 */
	public short generateKeyPair(byte[] dest, short destOff) {
		findFirstFreePosition();

		if ((addressIndex & 0xFF) == 0xFF) {
			ISOException.throwIt(StatusCodes.KEYSTORE_FULL);
		}

		// Generate the keys
		keyPair.genKeyPair();

		ECPublicKey pubKey = (ECPublicKey) keyPair.getPublic();

		// Calculate the key's Bitcoin address, the address is stored in
		// keyBuffer
		short addressLength = calculateBitcoinAddress(pubKey);

		Util.setShort(dest, (short) 2, addressLength);

		// Store address in the addressToKeyIndexMap
		addressToKeyIndexMap[addressIndex].setAddress(keyBuffer, (short) 0,
				addressLength);

		// Encrypt private key
		short keyLength = encryptPrivateKey(
				(ECPrivateKey) keyPair.getPrivate(), keyBuffer, (short) 0);

		// Store private key in this KeyStore
		keys[addressIndex].setKey(keyBuffer, (short) 0, keyLength);

		// Return the public key
		return pubKey.getW(dest, destOff);
	}

	/**
	 * Stores the given private key in the key store.
	 * 
	 * @param src The byte array, in which the address and private key can be
	 *            found.
	 * @param addrOff Offset for the address inside the byte array
	 * @param addrLength Length of the address
	 * @param keyOff Offset of the private key inside the byte array
	 * @param keyLength Length of the private key
	 */
	public void importPrivateKey(byte[] src, short addrOff, short addrLength,
			short keyOff, short keyLength) {
		calculateIndexForAddress(src, addrOff, addrLength);

		if ((addressIndex & 0xFF) != 0xFF) {
			ISOException.throwIt(StatusCodes.KEY_ALREADY_IN_STORE);
		}

		findFirstFreePosition();

		if (addressIndex == (byte) 0xFF) {
			ISOException.throwIt(StatusCodes.KEYSTORE_FULL);
		}

		validateBitcoinAddress(src, addrOff, addrLength);

		if (keyLength != 32) {
			ISOException.throwIt(StatusCodes.WRONG_PRIVATE_KEY_LENGTH);
		}

		addressToKeyIndexMap[addressIndex].setAddress(src, addrOff, addrLength);

		// Encrypt imported key and store in keys
		ECPrivateKey privKey = (ECPrivateKey) keyPair.getPrivate();
		privKey.setS(src, keyOff, keyLength);

		keys[addressIndex].setKey(keyBuffer, (short) 0,
				encryptPrivateKey(privKey, keyBuffer, (short) 0));
	}
	
	/**
	 * Stores the given encrypted private key in the key store.
	 * 
	 * @param src The byte array, in which the address and encrypted private key can be
	 *            found.
	 * @param addrOff Offset for the address inside the byte array
	 * @param addrLength Length of the address
	 * @param keyOff Offset of the private key inside the byte array
	 * @param keyLength Length of the private key
	 */
	public void importEncryptedPrivateKey(byte[] src, short addrOff, short addrLength,
			short keyOff, short keyLength) {
		calculateIndexForAddress(src, addrOff, addrLength);

		if ((addressIndex & 0xFF) != 0xFF) {
			ISOException.throwIt(StatusCodes.KEY_ALREADY_IN_STORE);
		}

		findFirstFreePosition();

		if (addressIndex == (byte) 0xFF) {
			ISOException.throwIt(StatusCodes.KEYSTORE_FULL);
		}

		validateBitcoinAddress(src, addrOff, addrLength);

		if (keyLength != 32) {
			ISOException.throwIt(StatusCodes.WRONG_PRIVATE_KEY_LENGTH);
		}

		addressToKeyIndexMap[addressIndex].setAddress(src, addrOff, addrLength);

		// Store imported key in keys
		keys[addressIndex].setKey(src, keyOff, keyLength);
	}

	/**
	 * Retrieves the encrypted private key for the given Bitcoin address.
	 * 
	 * @param src The byte array, in which the address and private key can be
	 *            found.
	 * @param addrOff Offset for the address inside the byte array
	 * @param addrLength Length of the address
	 * @param dest The output buffer in which the encrypted key will be
	 *            written.
	 * @param destOff The offset inside the output buffer
	 * 
	 * @return The size of the encrypted key in bytes
	 */
	public short getEncryptedPrivateKey(byte[] src, short addrOff,
			short addrLength, byte[] dest, short destOff) {

		validateBitcoinAddress(src, addrOff, addrLength);

		calculateIndexForAddress(src, addrOff, addrLength);

		if (addressIndex == 0xFF) {
			ISOException.throwIt(StatusCodes.KEY_NOT_FOUND);
		}

		return keys[addressIndex].getKey(dest, destOff);
	}

	/**
	 * Deletes the private key for the given address. If the key could not be
	 * found, nothing is done.
	 * 
	 * @param src The byte array, in which the address and private key can be
	 *            found.
	 * @param addrOff Offset for the address inside the byte array
	 * @param addrLength Length of the address
	 */
	public void deletePrivateKey(byte[] src, short addrOff, short addrLength)
			throws ISOException {
		validateBitcoinAddress(src, addrOff, (byte) addrLength);

		calculateIndexForAddress(src, addrOff, addrLength);
		if (addressIndex != (byte) 0xFF) {
			addressToKeyIndexMap[addressIndex].delete();
			keys[addressIndex].clear();
		} else {
			ISOException.throwIt(StatusCodes.KEY_NOT_FOUND);
		}
	}

	/**
	 * Calculates the current amount of registered private keys.
	 * 
	 * @return The current amount of registered private keys in this KeyStore
	 */
	public short getNumberOfKeys() {
		numberOfKeys = (short) 0;

		for (short i = 0; i < (short) keys.length; i++) {
			if (keys[i].inUse) {
				numberOfKeys = (short) (numberOfKeys + 1);
			}
		}

		return numberOfKeys;
	}

	/**
	 * Calculates the amount of free key slots.
	 */
	public short getNumberOfKeysRemaining() {
		return (short) (keys.length - getNumberOfKeys());
	}

	/**
	 * Whether this KeyStore is full.
	 */
	public boolean isFull() {
		return getNumberOfKeys() == (short) keys.length;
	}

	/**
	 * Calculates the index of the key for the given address in
	 * {@link #addressIndex}.
	 * 
	 * @param src The byte array in which the address can be found
	 * @param addrOff Offset of the address inside the given byte array
	 * @param addrLength Length of the address
	 */
	private void calculateIndexForAddress(byte[] src, short addrOff,
			short addrLength) {

		addressIndex = 0;

		while ((addressIndex & 0xFF) < addressToKeyIndexMap.length) {
			if (addressToKeyIndexMap[addressIndex].equalsAddress(src, addrOff,
					addrLength)) {
				return;
			}
			addressIndex++;
		}

		addressIndex = (byte) 0xFF;
	}

	/**
	 * Calculates the index of the first free slot inside the key array in
	 * {@link #addressIndex}. If no free slot could be found, addressIndex is
	 * set to 255 (0xFF).
	 */
	private void findFirstFreePosition() {
		addressIndex = 0;
		while ((addressIndex & 0xFF) < keys.length) {
			if (!keys[addressIndex].isInUse()) {
				return;
			}
			addressIndex++;
		}
		addressIndex = (byte) 0xFF;
	}

	/**
	 * Encrypts the given private key with the AES key of this store.</br>
	 * Uses {@link #encryptionBuffer}.</br>
	 * If the length is too short to suit the 128bits block size, additional
	 * random values are appended.
	 * 
	 * @param privateKey The private key to encrypt
	 * @param dest The destination, where the encrypted key is put
	 * @param destOff The offset inside the destination array
	 * 
	 * @return The length of the encrypted key.
	 */
	private short encryptPrivateKey(ECPrivateKey privateKey, byte[] dest,
			short destOff) {
		short keyLength = privateKey.getS(encryptionBuffer, (short) 0);

		if (keyLength != PRIVATE_KEY_SIZE) {
			ISOException.throwIt(StatusCodes.WRONG_PRIVATE_KEY_LENGTH);
		}

		aesCipher.init(aesKey, Cipher.MODE_ENCRYPT);

		return aesCipher.doFinal(encryptionBuffer, (short) 0, PRIVATE_KEY_SIZE,
				dest, destOff);
	}

	/**
	 * Decrypts the given private key with the AES key of this store.</br>
	 * Uses {@link #encryptionBuffer}.
	 * 
	 * @param privateKey The private key to decrypt
	 * @param dest The destination, where the decrypted key is put
	 * @param destOff The offset inside the destination array
	 * 
	 * @return The length of the decrypted key.
	 */
	private short decryptPrivateKey(EncryptedPrivateKey privateKey,
			byte[] dest, short destOff) {
		short keyLength = privateKey.getKey(encryptionBuffer, (short) 0);

		if (keyLength != PRIVATE_KEY_SIZE) {
			ISOException.throwIt(StatusCodes.WRONG_PRIVATE_KEY_LENGTH);
		}

		aesCipher.init(aesKey, Cipher.MODE_DECRYPT);
		aesCipher.doFinal(encryptionBuffer, (short) 0, keyLength,
				encryptionBuffer, (short) 0);

		Util.arrayCopy(encryptionBuffer, (short) 0, dest, destOff,
				PRIVATE_KEY_SIZE);

		return keyLength;
	}

	/**
	 * Calculates the Bitcoin address from the given public key. The address
	 * will be stored in the {@link #keyBuffer}.
	 * </br>
	 * Uses {@link #hashBuffer}.
	 * </br>
	 * See <a href=
	 * "https://en.bitcoin.it/wiki/Technical_background_of_version_1_Bitcoin_addresses#How_to_create_Bitcoin_Address"
	 * >How to create Bitcoin Address</a>
	 * 
	 * @param src The byte array in which the public key can be found
	 * @param keyOff Offset of the key inside the given byte array
	 * @param keyLength Length of the public key
	 * 
	 * @return The length of the calculated bitcoin address inside the
	 *         {@link #keyBuffer}
	 */
	private short calculateBitcoinAddress(ECPublicKey pubKey) {

		// Get key bytes
		short keyLength = pubKey.getW(keyBuffer, (short) 0);

		// Calculate sha256 hash of public key
		short sha256Length;
		sha256Length = sha256Digest.doFinal(keyBuffer, (short) 0, keyLength,
				keyBuffer, (short) 0);

		// Calculate ripemd160 hash of sha256 hash
		Ripemd160.hash32(keyBuffer, (short) 0, keyBuffer, (short) 1,
				hashBuffer, (short) 0);

		short ripemd160Length = 20;

		// Add version byte (0x00 for Main Network)
		keyBuffer[0] = (byte) 0x00;

		// Calculate sha256 hash of extended RIPEMD-160 result
		sha256Length = sha256Digest.doFinal(keyBuffer, (short) 0,
				(short) (ripemd160Length + 1), keyBuffer,
				(short) (ripemd160Length + 1));

		// Calculate sha256 hash on the result of the previous SHA-256 hash
		sha256Length = sha256Digest.doFinal(keyBuffer,
				(short) (ripemd160Length + 1), sha256Length, keyBuffer,
				(short) (ripemd160Length + 1));

		// Calculate the base58 encoded address and return its length
		return Base58.encode(keyBuffer, (short) 0,
				(short) (ripemd160Length + 5), keyBuffer, (short) 0,
				hashBuffer, (short) 0);
	}

	/**
	 * Validates the given Bitcoin address for correctness.
	 * 
	 * @param src The buffer containing the Bitcoin address
	 * @param addrOff Offset of the address inside the buffer
	 * @param addrLength Length of the address
	 */
	private void validateBitcoinAddress(byte[] src, short addrOff,
			short addrLength) throws ISOException {
		if (addrLength < 26 || addrLength > 35) {
			ISOException.throwIt(StatusCodes.WRONG_ADDRESS_LENGTH);
		}

		// TODO verify checksum?
	}
}
