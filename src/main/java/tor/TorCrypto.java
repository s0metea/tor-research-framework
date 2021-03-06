/*
        Tor Research Framework - easy to use tor client library/framework
        Copyright (C) 2014  Dr Gareth Owen <drgowen@gmail.com>
        www.ghowen.me / github.com/drgowen/tor-research-framework

        This program is free software: you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation, either version 3 of the License, or
        (at your option) any later version.

        This program is distributed in the hope that it will be useful,
        but WITHOUT ANY WARRANTY; without even the implied warranty of
        MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
        GNU General Public License for more details.

        You should have received a copy of the GNU General Public License
        along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package tor;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.lang.ArrayUtils;
import org.bouncycastle.asn1.*;
import org.bouncycastle.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Enumeration;

public class TorCrypto {
    public static SecureRandom rnd = new SecureRandom();
    public final static int KEY_LEN = 16;
    public final static int DH_LEN = 128;
    public final static int DH_SEC_LEN = 40;
    public final static int PK_ENC_LEN = 128;
    public final static int PK_PAD_LEN = 42;
    public final static int HASH_LEN = 20;
    public static BigInteger DH_G = new BigInteger("2");
    public static BigInteger DH_P = new BigInteger("179769313486231590770839156793787453197860296048756011706444423684197180216158519368947833795864925541502180565485980503646440548199239100050792877003355816639229553136239076508735759914822574862575007425302077447712589550957937778424442426617334727629299387668709205606050270810842907692932019128194467627007");

    public TorCrypto() throws NoSuchAlgorithmException,
            NoSuchProviderException, NoSuchPaddingException {

    }

    /**
     * BigInteger to byte array, stripping leading zero if applicable (e.g. unsigned data only)
     *
     * @param in BigInteger
     * @return
     */
    public static byte[] BNtoByte(BigInteger in) {
        byte[] intmp = in.toByteArray();
        if (intmp[0] != 0)
            return intmp;

        byte intmp2[] = new byte[intmp.length - 1];
        System.arraycopy(intmp, 1, intmp2, 0, intmp2.length);
        return intmp2;
    }

    // add zero sign byte so always positive

    /**
     * Converts byte array to BigInteger, adding zero sign byte to make unsigned.
     *
     * @param in BigInteger
     * @return
     */
    public static BigInteger byteToBN(byte in[]) {
        byte tmp[] = new byte[in.length + 1];
        tmp[0] = 0;
        System.arraycopy(in, 0, tmp, 1, in.length);
        return new BigInteger(tmp);
    }

    public static MessageDigest getSHA1() {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        return md;
    }

    /**
     * Tor Key derivation function
     *
     * @param secret A secret
     * @param length Length of key data to generate
     * @return Key data
     */
    public static byte[] torKDF(byte[] secret, int length) {
        byte data[] = new byte[(int) Math.ceil(length / (double) HASH_LEN) * HASH_LEN];
        byte hashdata[] = new byte[secret.length + 1];

        assert secret.length == DH_LEN;  // checks if secret is length of diffie-hellman - might not be applicable in some cases

        //System.out.println("sec len " + secret.length);

        System.arraycopy(secret, 0, hashdata, 0, secret.length);

        for (int i = 0; i < data.length / HASH_LEN; i++) {
            hashdata[secret.length] = (byte) i;
            MessageDigest md;
            try {
                md = MessageDigest.getInstance("SHA-1");
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
            System.arraycopy(md.digest(hashdata), 0, data, i * HASH_LEN, HASH_LEN);
        }
        return data;
    }

    /**
     * Tor Hybrid Encrypt function
     *
     * @param in Data to encrypt
     * @param pk Onion Router public key to encrypt to
     * @return Encrypted data
     */
    public static byte[] hybridEncrypt(byte[] in, PublicKey pk) {
        try {
            Cipher rsa = Cipher.getInstance("RSA/None/OAEPWithSHA1AndMGF1Padding", "BC");
            rsa.init(Cipher.ENCRYPT_MODE, pk);
            if (in.length < PK_ENC_LEN - PK_PAD_LEN) {
                return rsa.doFinal(in);
            } else {
                // prep key and IV
                byte[] key = new byte[KEY_LEN];
                rnd.nextBytes(key);
                byte[] iv = new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
                SecretKeySpec keysp = new SecretKeySpec(key, "AES");
                IvParameterSpec ivSpec = new IvParameterSpec(iv);

                // prepare m1
                byte m1a[] = Arrays.copyOfRange(in, 0, PK_ENC_LEN - PK_PAD_LEN - KEY_LEN);
                byte m1[] = ArrayUtils.addAll(key, m1a);
                byte rsaciphertext[] = rsa.doFinal(m1);

                // prepare m2
                byte m2[] = Arrays.copyOfRange(in, m1a.length, in.length);
                Cipher aes = Cipher.getInstance("AES/CTR/NoPadding");
                aes.init(Cipher.ENCRYPT_MODE, keysp, ivSpec);
                byte aesciphertext[] = aes.doFinal(m2);

                // merge
                return ArrayUtils.addAll(rsaciphertext, aesciphertext);
            }
        } catch (BadPaddingException | NoSuchAlgorithmException | NoSuchProviderException | NoSuchPaddingException | InvalidKeyException | IllegalBlockSizeException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses a public key encoded as ASN.1
     *
     * @param rsapublickey ASN.1 Encoded public key
     * @return PublicKey
     */

    public static RSAPrivateKey asn1GetPrivateKey(byte[] rsapkbuf) {
        ASN1InputStream bIn = new ASN1InputStream(new ByteArrayInputStream(rsapkbuf));
        try {
            DLSequence obj = (DLSequence) bIn.readObject();
            ASN1Integer mod = (ASN1Integer) obj.getObjectAt(1);
            ASN1Integer pubExp = (ASN1Integer) obj.getObjectAt(2);
            ASN1Integer privExp = (ASN1Integer) obj.getObjectAt(3);

            RSAPrivateKey privKey = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateKeySpec(mod.getValue(), privExp.getValue()));
            return privKey;
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            e.printStackTrace();
        }

        return null;
    }

    // get public key from encoded private key
    public static RSAPublicKey asn1GetPrivateKeyPublic(byte[] rsapkbuf) {
        ASN1InputStream bIn = new ASN1InputStream(new ByteArrayInputStream(rsapkbuf));
        try {
            DLSequence obj = (DLSequence) bIn.readObject();
            ASN1Integer mod = (ASN1Integer) obj.getObjectAt(1);
            ASN1Integer pubExp = (ASN1Integer) obj.getObjectAt(2);
            ASN1Integer privExp = (ASN1Integer) obj.getObjectAt(3);

            RSAPublicKey pubKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(mod.getValue(), pubExp.getValue()));
            return pubKey;
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            e.printStackTrace();
        }

        return null;
    }


    public static PublicKey pubKeyFromPrivate(RSAPrivateKey priv) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(priv.getModulus(), new BigInteger("65537")));
        } catch (InvalidKeySpecException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }
    /**
     * Parses a public key encoded as ASN.1
     *
     * @param rsapublickey ASN.1 Encoded public key
     * @return PublicKey
     */
    public static PublicKey asn1GetPublicKey(byte[] rsapublickey) {
        int blobsize = rsapublickey.length;
        DataInputStream dis = null;
        int jint = 0; // int to represent unsigned byte or unsigned short
        int datacount = 0;

        try {
            // --- Try to read the ANS.1 encoded RSAPublicKey blob -------------
            ByteArrayInputStream bis = new ByteArrayInputStream(rsapublickey);
            dis = new DataInputStream(bis);

            if (dis.readByte() != 0x30) // asn.1 encoded starts with 0x30
                return null;

            jint = dis.readUnsignedByte(); // asn.1 is 0x80 plus number of bytes
            // representing data count
            if (jint == 0x81)
                datacount = dis.readUnsignedByte(); // datalength is specified
                // in next byte.
            else if (jint == 0x82) // bytes count for any supported keysize
                // would be at most 2 bytes
                datacount = dis.readUnsignedShort(); // datalength is specified
                // in next 2 bytes
            else
                return null; // all supported publickey byte-sizes can be
            // specified in at most 2 bytes

            if ((jint - 0x80 + 2 + datacount) != blobsize) // sanity check for
                // correct number of
                // remaining bytes
                return null;

            //		System.out
            //			.println("\nRead outer sequence bytes; validated outer asn.1 consistency ");

            // ------- Next attempt to read Integer sequence for modulus ------
            if (dis.readUnsignedByte() != 0x02) // next byte read must be
                // Integer asn.1 specifier
                return null;
            jint = dis.readUnsignedByte(); // asn.1 is 0x80 plus number of bytes
            // representing data count
            if (jint == 0x81)
                datacount = dis.readUnsignedByte(); // datalength is specified
                // in next byte.
            else if (jint == 0x82) // bytes count for any supported keysize
                // would be at most 2 bytes
                datacount = dis.readUnsignedShort(); // datalength is specified
                // in next 2 bytes
            else
                return null; // all supported publickey modulus byte-sizes can
            // be specified in at most 2 bytes

            // ---- next bytes are big-endian ordered modulus -----
            byte[] modulus = new byte[datacount];
            int modbytes = dis.read(modulus);
            if (modbytes != datacount) // if we can read enought modulus bytes
                // ...
                return null;

            //System.out.println("Read modulus");

            // ------- Next attempt to read Integer sequence for public exponent
            // ------
            if (dis.readUnsignedByte() != 0x02) // next byte read must be
                // Integer asn.1 specifier
                return null;
            datacount = dis.readUnsignedByte(); // size of modulus is specified
            // in one byte
            byte[] exponent = new byte[datacount];
            int expbytes = dis.read(exponent);
            if (expbytes != datacount)
                return null;
            //System.out.println("Read exponent");

            // ----- Finally, create the PublicKey object from modulus and
            // public exponent --------
            RSAPublicKeySpec pubKeySpec = new RSAPublicKeySpec(new BigInteger(
                    1, modulus), new BigInteger(1, exponent));
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey pubKey = keyFactory.generatePublic(pubKeySpec);
            return pubKey;
        } catch (Exception exc) {
            return null;
        } finally {
            try {
                dis.close();
            } catch (Exception exc) {
                /* ignore */
                ;
            }
        }
    }

    public static byte [] publicKeyToASN1(RSAPublicKey pk) throws IOException {
        byte[] modulus = pk.getModulus().toByteArray();
        byte[] pubexp = pk.getPublicExponent().toByteArray();

        ByteBuffer inner = ByteBuffer.allocate(1024);
        inner.put((byte)2); // Integer
        inner.put((byte)0x81); // one byte size
        inner.put((byte)modulus.length); // one byte size
        inner.put(modulus);

        inner.put((byte)2); // Integer
        //inner.put((byte)0x81); // one byte size
        inner.put((byte)pubexp.length); // one byte size
        inner.put(pubexp);
        inner.flip();

        ByteBuffer outer = ByteBuffer.allocate(1024);
        outer.put((byte)0x30); // SEQUENCE
        outer.put((byte)0x81); // one byte size
        outer.put((byte)inner.limit()); // one byte size
        outer.put(inner);
        outer.flip();

        byte asn[] = new byte[outer.limit()];
        outer.get(asn);
        return asn;
    }

//    public static byte [] publicKeyToPKCS1(RSAPublicKey pk) throws IOException {
//        byte pkbits[] = publicKeyToASN1(pk);
//
//        ByteBuffer buf = ByteBuffer.allocate(1024);
//        buf.put(new byte[] {0x30, 0x0d, //<- a SEQUENCE with 0d bytes
//                0x06, 0x09, 0x2a, (byte)0x86, 0x48, (byte)0x86, (byte)0xf7, 0x0d, 0x01, 0x01, 0x01,  //<- OID 1.2.840.113549.1.1.1
//                0x05, 0x00}); // NULL
//        buf.put(new byte[] {0x03, (byte)0x81, (byte)pkbits.length, 0x00} ); // bit string
//        buf.put(pkbits); // public key bits as a sequence
//
//        buf.flip();
//
//        //overall sequence header
//        ByteBuffer outer = ByteBuffer.allocate(256);
//        outer.put((byte)0x30);
//        outer.put((byte)0x81);
//        outer.put((byte)buf.limit());
//        outer.put(buf);
//        outer.flip();
//
//        byte out[] = new byte[outer.limit()];
//        outer.get(out);
//        return out;
//
//
//
//    }
}
