package de.plmail.crypto

import java.io.StringReader
import java.security.MessageDigest
import java.time.Instant
import org.bouncycastle.asn1.ASN1IA5String
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.RSAESOAEPparams
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.CMSAuthEnvelopedData
import org.bouncycastle.cms.CMSEnvelopedData
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.DefaultCMSSignatureAlgorithmNameGenerator
import org.bouncycastle.cms.KeyTransRecipientInformation
import org.bouncycastle.cms.RecipientInformation
import org.bouncycastle.cms.RecipientInformationStore
import org.bouncycastle.cms.bc.BcRSAKeyTransEnvelopedRecipient
import org.bouncycastle.cms.bc.BcRSASignerInfoVerifierBuilder
import org.bouncycastle.crypto.CipherParameters
import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.encodings.OAEPEncoding
import org.bouncycastle.crypto.engines.RSABlindedEngine
import org.bouncycastle.crypto.params.AsymmetricKeyParameter
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcDefaultDigestProvider
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider
import org.bouncycastle.util.Selector

/**
 * One of the person's own S/MIME certificates, with its private key opened (#47).
 *
 * `Keyring/get` serves each as a PEM certificate and the PKCS#8 private key wrapped directly under
 * a user key with the `smimeKey` purpose — no token and no passphrase, unlike an OpenPGP address
 * key. Every one decrypts, retired and expired ones included, because mail encrypted to them is
 * still the person's mail; only an [isActive] one that [servesAt] a moment signs.
 */
class UnlockedSmimeKey
internal constructor(
    /** SHA-256 of the certificate's DER, lower-case hex: how the server names it. */
    val fingerprint: String,
    val addresses: List<String>,
    val isActive: Boolean,
    val notBefore: Instant?,
    val notAfter: Instant?,
    internal val certificate: X509CertificateHolder,
    internal val chain: List<X509CertificateHolder>,
    internal val privateKey: AsymmetricKeyParameter,
) {
    /** Whether it may sign mail from [address] at [now]: active, for that address, valid then. */
    fun servesAt(address: String, now: Instant = Instant.now()): Boolean =
        isActive &&
            addresses.any { it.equals(address.trim(), ignoreCase = true) } &&
            (notBefore == null || !now.isBefore(notBefore)) &&
            (notAfter == null || now.isBefore(notAfter))

    override fun toString(): String = "UnlockedSmimeKey($fingerprint)"
}

/** A certificate or key from the keyring that cannot be read, or whose wrapping does not open. */
class SmimeKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

object SmimeKeys {

    /**
     * Opens a `smimeCertificates` entry with the user key [userKey] it is wrapped under.
     *
     * Throws [SmimeKeyException] for a certificate that is not one, a wrapping this user key does
     * not open, or a private key that is not PKCS#8.
     */
    fun unlock(
        certificatePem: String,
        chainPem: String,
        wrappedPrivateKey: String,
        userKey: UserKey,
        addresses: List<String>,
        isActive: Boolean,
        notBefore: Instant? = null,
        notAfter: Instant? = null,
        purpose: String = Purposes.SMIME_KEY,
    ): UnlockedSmimeKey {
        val certificate =
            certificates(certificatePem).firstOrNull()
                ?: throw SmimeKeyException("No certificate in the keyring entry.")

        val key = userKey.bytes
        val pkcs8 =
            try {
                Wrapping.open(key, wrappedPrivateKey, purpose)
            } catch (failed: WrappingException) {
                throw SmimeKeyException("The private key's wrapping does not open.", failed)
            } finally {
                key.fill(0)
            }

        val privateKey =
            try {
                PrivateKeyFactory.createKey(pkcs8)
            } catch (failed: Exception) {
                throw SmimeKeyException("The private key is not PKCS#8.", failed)
            } finally {
                pkcs8.fill(0)
            }

        return UnlockedSmimeKey(
            fingerprint = fingerprint(certificate),
            addresses = addresses,
            isActive = isActive,
            notBefore = notBefore,
            notAfter = notAfter,
            certificate = certificate,
            chain = certificates(chainPem),
            privateKey = privateKey,
        )
    }

    /** SHA-256 over a PEM certificate's DER, as the server computes it; null if it is not one. */
    fun fingerprint(certificatePem: String): String? =
        certificates(certificatePem).firstOrNull()?.let(::fingerprint)

    internal fun fingerprint(certificate: X509CertificateHolder): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") {
            "%02x".format(it)
        }

    /** Every certificate in [pem], in order; empty for blank or unreadable text. */
    internal fun certificates(pem: String): List<X509CertificateHolder> {
        if (pem.isBlank()) return emptyList()

        return try {
            PEMParser(StringReader(pem)).use { parser ->
                generateSequence { parser.readObject() }
                    .filterIsInstance<X509CertificateHolder>()
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The mail addresses a certificate names: its rfc822 subject alternative names and
     * emailAddress.
     */
    internal fun addressesOf(certificate: X509CertificateHolder): List<String> {
        val alternative =
            certificate
                .getExtension(Extension.subjectAlternativeName)
                ?.let { extension ->
                    GeneralNames.getInstance(extension.parsedValue)
                        .names
                        .filter { it.tagNo == GeneralName.rfc822Name }
                        .map { (it.name as ASN1IA5String).string }
                }
                .orEmpty()

        val subject =
            certificate.subject.getRDNs(BCStyle.EmailAddress).map {
                IETFUtils.valueToString(it.first.value)
            }

        return (alternative + subject).map { it.trim().lowercase() }.distinct()
    }
}

/**
 * Opening S/MIME: the envelope (CMS EnvelopedData, or AuthEnvelopedData) with the person's own
 * certificates, and the signature inside it — a `multipart/signed` entity or an opaque SignedData
 * (#47).
 *
 * Bouncy Castle's lightweight operators throughout, never the JCA: Android ships its own, stripped
 * "BC" provider, and registering a second one under that name is how apps end up with whichever of
 * the two the platform found first. The ciphers are what Bouncy Castle opens — AES-CBC and AES-GCM,
 * and 3DES and RC2 that old Outlook still sends, which the web app turns away.
 */
internal object Smime {

    /** Decrypts [der], a CMS ContentInfo, with any of [keys]. */
    fun decrypt(der: ByteArray, keys: List<UnlockedSmimeKey>): ByteArray {
        if (keys.isEmpty()) throw DecryptionException(Reason.NO_CERTIFICATE)

        val recipients: RecipientInformationStore =
            try {
                CMSEnvelopedData(der).recipientInfos
            } catch (_: CMSException) {
                try {
                    CMSAuthEnvelopedData(der).recipientInfos
                } catch (unreadable: Exception) {
                    throw DecryptionException(Reason.MALFORMED, unreadable)
                }
            } catch (unreadable: Exception) {
                throw DecryptionException(Reason.MALFORMED, unreadable)
            }

        val transports = recipients.recipients.filterIsInstance<KeyTransRecipientInformation>()
        var unsupported: Exception? = null
        var last: Exception? = null

        // The certificate the envelope names first, then every key against
        // every recipient: a sender that names a recipient by a subject key
        // identifier the certificate lacks must not lock the person out.
        val attempts =
            keys
                .flatMap { key ->
                    transports.map { recipient -> key to recipient }
                }
                .sortedByDescending { (key, recipient) -> recipient.rid.match(key.certificate) }

        attempts.forEach { (key, recipient) ->
            try {
                return content(recipient, key)
            } catch (failed: CMSException) {
                if (failed.isUnsupportedCipher()) unsupported = failed
                last = failed
            } catch (failed: Exception) {
                // Not this key.
                last = failed
            }
        }

        if (unsupported != null) throw DecryptionException(Reason.UNSUPPORTED, unsupported)
        throw DecryptionException(Reason.NO_KEY, last)
    }

    private fun content(recipient: RecipientInformation, key: UnlockedSmimeKey): ByteArray =
        recipient.getContent(OaepAwareRecipient(key.privateKey))

    /**
     * Bouncy Castle's lightweight RSA recipient, taught RSA-OAEP key transport.
     *
     * Its unwrapper reads every RSA key transport as PKCS#1 v1.5 and fails OAEP with "block
     * incorrect", while the web app opens OAEP (with WebCrypto) and some clients send it. The
     * parameters come from the message — hash and mask hash default to SHA-1, as RFC 4055 says —
     * and only the content key is unwrapped here; the content cipher stays Bouncy Castle's.
     */
    private class OaepAwareRecipient(private val privateKey: AsymmetricKeyParameter) :
        BcRSAKeyTransEnvelopedRecipient(privateKey) {

        override fun extractSecretKey(
            keyEncryptionAlgorithm: AlgorithmIdentifier,
            encryptedKeyAlgorithm: AlgorithmIdentifier,
            encryptedEncryptionKey: ByteArray,
        ): CipherParameters {
            if (keyEncryptionAlgorithm.algorithm != PKCSObjectIdentifiers.id_RSAES_OAEP) {
                return super.extractSecretKey(
                    keyEncryptionAlgorithm,
                    encryptedKeyAlgorithm,
                    encryptedEncryptionKey,
                )
            }

            val parameters = RSAESOAEPparams.getInstance(keyEncryptionAlgorithm.parameters)
            val digest = BcDefaultDigestProvider.INSTANCE.get(parameters.hashAlgorithm)
            val maskDigest =
                BcDefaultDigestProvider.INSTANCE.get(
                    AlgorithmIdentifier.getInstance(parameters.maskGenAlgorithm.parameters)
                )
            val label = ASN1OctetString.getInstance(parameters.pSourceAlgorithm.parameters).octets

            val oaep = OAEPEncoding(RSABlindedEngine(), digest, maskDigest, label)
            oaep.init(false, privateKey)

            return try {
                KeyParameter(
                    oaep.processBlock(encryptedEncryptionKey, 0, encryptedEncryptionKey.size)
                )
            } catch (wrongKey: InvalidCipherTextException) {
                throw CMSException("Not this key.", wrongKey)
            }
        }
    }

    private fun CMSException.isUnsupportedCipher(): Boolean =
        generateSequence<Throwable>(this) { it.cause }
            .any {
                val message = it.message.orEmpty().lowercase()
                "cannot recognise" in message ||
                    "can't find" in message ||
                    "not supported" in message
            }

    /** A signature found inside, with what it was made with. */
    class Signed(val content: ByteArray, val signature: InnerSignature)

    /**
     * A `multipart/signed` entity, RFC 8551 §3.5.3: the signed bytes are exactly what lies between
     * the first two delimiters — the CRLF before a delimiter belongs to the delimiter — and the
     * signature is the second part.
     */
    fun detached(raw: ByteArray, boundary: String): Signed? {
        val text = String(raw, Charsets.ISO_8859_1)
        val delimiter = "--$boundary"
        val first = text.indexOf(delimiter).takeIf { it >= 0 } ?: return null
        val contentStart = text.indexOf('\n', first).takeIf { it >= 0 }?.plus(1) ?: return null
        val next = text.indexOf("\n$delimiter", contentStart).takeIf { it >= 0 } ?: return null
        // The line break before the delimiter is the delimiter's: CRLF on the
        // wire, a bare LF from a writer that never canonicalised.
        val end = if (next > contentStart && text[next - 1] == '\r') next - 1 else next
        val signed = raw.copyOfRange(contentStart, end)

        val signaturePart = text.substring(next + 1 + delimiter.length)
        val headersEnd =
            Regex("\r?\n\r?\n").find(signaturePart)?.range?.last?.plus(1) ?: return null
        val base64 = signaturePart.substring(headersEnd).substringBefore("--$boundary")
        val der =
            try {
                java.util.Base64.getMimeDecoder().decode(base64.trim())
            } catch (_: IllegalArgumentException) {
                return Signed(signed, InnerSignature.Invalid(""))
            }

        return Signed(signed, verify(CMSSignedData(CMSProcessableByteArray(signed), der)))
    }

    /** An opaque SignedData: the content it carries, and its signature. */
    fun opaque(der: ByteArray): Signed {
        val signedData =
            try {
                CMSSignedData(der)
            } catch (unreadable: Exception) {
                throw DecryptionException(Reason.MALFORMED, unreadable)
            }

        val content =
            signedData.signedContent?.content as? ByteArray
                ?: throw DecryptionException(Reason.MALFORMED)

        return Signed(content, verify(signedData))
    }

    /**
     * The first signer, checked against the certificate the message carries for it, as the web app
     * checks it (`smime.verify()`): no chain check here — whose certificate it is, and whether
     * anybody vouched for it, is for the caller to judge.
     */
    private fun verify(signed: CMSSignedData): InnerSignature {
        val signer = signed.signerInfos.signers.firstOrNull() ?: return InnerSignature.None

        @Suppress("UNCHECKED_CAST")
        val certificate =
            signed.certificates
                .getMatches(signer.sid as Selector<X509CertificateHolder>)
                .firstOrNull() ?: return InnerSignature.Invalid("")

        val intact =
            try {
                signer.verify(
                    BcRSASignerInfoVerifierBuilder(
                            DefaultCMSSignatureAlgorithmNameGenerator(),
                            DefaultSignatureAlgorithmIdentifierFinder(),
                            DefaultDigestAlgorithmIdentifierFinder(),
                            BcDigestCalculatorProvider(),
                        )
                        .build(certificate)
                )
            } catch (_: Exception) {
                false
            }

        return InnerSignature.Certificate(
            fingerprint = SmimeKeys.fingerprint(certificate),
            addresses = SmimeKeys.addressesOf(certificate),
            isIntact = intact,
        )
    }

    fun String.isPkcs7Mime(): Boolean =
        equals("application/pkcs7-mime", ignoreCase = true) ||
            equals("application/x-pkcs7-mime", ignoreCase = true)
}
