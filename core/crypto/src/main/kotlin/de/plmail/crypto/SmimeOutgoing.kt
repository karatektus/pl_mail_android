package de.plmail.crypto

import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.cms.AttributeTable
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.smime.SMIMECapabilitiesAttribute
import org.bouncycastle.asn1.smime.SMIMECapability
import org.bouncycastle.asn1.smime.SMIMECapabilityVector
import org.bouncycastle.cms.CMSAlgorithm
import org.bouncycastle.cms.CMSEnvelopedDataGenerator
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.DefaultSignedAttributeTableGenerator
import org.bouncycastle.cms.SignerInfoGeneratorBuilder
import org.bouncycastle.cms.bc.BcCMSContentEncryptorBuilder
import org.bouncycastle.cms.bc.BcRSAKeyTransRecipientInfoGenerator
import org.bouncycastle.operator.DefaultDigestAlgorithmIdentifierFinder
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider
import org.bouncycastle.operator.bc.BcRSAContentSignerBuilder
import org.bouncycastle.util.CollectionStore

/**
 * Signing and enveloping for S/MIME sending (#47), as `assets/crypto/smime.js` does it in the web
 * app: a detached SignedData with SHA-256 and RSA PKCS#1 v1.5, and an EnvelopedData with
 * AES-256-CBC and RSA PKCS#1 v1.5 key transport, recipients named by issuer and serial number.
 * Bouncy Castle's lightweight operators, for the same reason as in [Smime].
 */
internal object SmimeOutgoing {

    private const val CRLF = "\r\n"

    /**
     * [entity] as a `multipart/signed` entity, the signature over the first part's bytes exactly as
     * they stand — the entity with its trailing line break removed, since the CRLF before the
     * delimiter belongs to the delimiter.
     */
    fun sign(entity: ByteArray, signer: UnlockedSmimeKey): ByteArray {
        val signed = String(entity, Charsets.UTF_8).removeSuffix(CRLF).toByteArray(Charsets.UTF_8)
        val signature = signature(signed, signer)
        val boundary = OutgoingMessages.boundary()

        val out = StringBuilder()
        out.append("Content-Type: multipart/signed; protocol=\"application/pkcs7-signature\";")
        out.append(CRLF).append(" micalg=sha-256; boundary=\"").append(boundary).append('"')
        out.append(CRLF).append(CRLF)
        out.append("This is a cryptographically signed message in MIME format.").append(CRLF)
        out.append(CRLF)
        out.append("--").append(boundary).append(CRLF)

        val head = out.toString().toByteArray(Charsets.UTF_8)

        val tail = StringBuilder()
        tail.append(CRLF).append("--").append(boundary).append(CRLF)
        tail.append("Content-Type: application/pkcs7-signature; name=\"smime.p7s\"").append(CRLF)
        tail.append("Content-Transfer-Encoding: base64").append(CRLF)
        tail.append("Content-Disposition: attachment; filename=\"smime.p7s\"").append(CRLF)
        tail.append("Content-Description: S/MIME Cryptographic Signature").append(CRLF)
        tail.append(CRLF)
        tail.append(OutgoingMessages.base64Lines(signature))
        tail.append(CRLF)
        tail.append("--").append(boundary).append("--").append(CRLF)

        return head + signed + tail.toString().toByteArray(Charsets.UTF_8)
    }

    /** A detached SignedData (DER) over [content]. */
    private fun signature(content: ByteArray, signer: UnlockedSmimeKey): ByteArray {
        val algorithm = DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withRSA")
        val digest = DefaultDigestAlgorithmIdentifierFinder().find(algorithm)
        val contentSigner = BcRSAContentSignerBuilder(algorithm, digest).build(signer.privateKey)

        // What the signer can read, so a reply comes back in a cipher it opens:
        // the web app's list, strongest first.
        val capabilities =
            SMIMECapabilityVector().apply {
                addCapability(SMIMECapability.aES256_CBC)
                addCapability(SMIMECapability.aES192_CBC)
                addCapability(SMIMECapability.aES128_CBC)
                addCapability(PKCSObjectIdentifiers.rsaEncryption)
            }
        val attributes =
            ASN1EncodableVector().apply { add(SMIMECapabilitiesAttribute(capabilities)) }

        val signerInfo =
            SignerInfoGeneratorBuilder(BcDigestCalculatorProvider())
                .setSignedAttributeGenerator(
                    DefaultSignedAttributeTableGenerator(AttributeTable(attributes))
                )
                .build(contentSigner, signer.certificate)

        val generator =
            CMSSignedDataGenerator().apply {
                addSignerInfoGenerator(signerInfo)
                addCertificates(CollectionStore(listOf(signer.certificate) + signer.chain))
            }

        return generator.generate(CMSProcessableByteArray(content), false).encoded
    }

    /**
     * [content] enveloped for every certificate in [recipients] (PEM) and for [signer]'s own, so
     * the sender can read what they sent. Throws [IllegalArgumentException] for a certificate that
     * cannot be read or is not RSA.
     */
    fun envelope(
        content: ByteArray,
        recipients: List<String>,
        signer: UnlockedSmimeKey,
    ): ByteArray {
        val certificates =
            recipients.map { pem ->
                SmimeKeys.certificates(pem).firstOrNull()
                    ?: throw IllegalArgumentException("A recipient's certificate cannot be read.")
            } + signer.certificate

        val generator = CMSEnvelopedDataGenerator()
        certificates
            .distinctBy { SmimeKeys.fingerprint(it) }
            .forEach { certificate ->
                generator.addRecipientInfoGenerator(
                    BcRSAKeyTransRecipientInfoGenerator(certificate)
                )
            }

        return generator
            .generate(
                CMSProcessableByteArray(content),
                BcCMSContentEncryptorBuilder(CMSAlgorithm.AES256_CBC).build(),
            )
            .encoded
    }
}
