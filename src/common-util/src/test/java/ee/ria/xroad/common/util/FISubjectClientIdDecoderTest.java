/**
 * The MIT License
 * Copyright (c) 2015 Estonian Information System Authority (RIA), Population Register Centre (VRK)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package ee.ria.xroad.common.util;

import ee.ria.xroad.common.CodedException;
import ee.ria.xroad.common.identifier.ClientId;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.Assert.assertEquals;

/**
 * Created by hyoty on 25.8.2015.
 */
public class FISubjectClientIdDecoderTest {

    private static KeyPair keyPair;
    @BeforeClass
    public static void init() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    @Test
    public void shouldDecodeClientId() throws GeneralSecurityException, IOException, OperatorCreationException {
        final X509Certificate cert = generateSelfSignedCertificate("C=FI, O=FI-TEST, OU=PUB, CN=1234567-8", keyPair);
        ClientId clientId = FISubjectClientIdDecoder.getSubjectClientId(cert);
        assertEquals(ClientId.create("FI-TEST", "PUB", "1234567-8"), clientId);
    }

    @Test(expected = CodedException.class)
    public void shouldFailIfCountryDoesNotMatch() throws GeneralSecurityException, IOException, OperatorCreationException {
        final X509Certificate cert = generateSelfSignedCertificate("C=XX, O=FI-TEST, OU=PUB, CN=1234567-8", keyPair);
        FISubjectClientIdDecoder.getSubjectClientId(cert);
    }

    @Test(expected = CodedException.class)
    public void shouldFailIfComponentMissing() throws GeneralSecurityException, IOException, OperatorCreationException {
        final X509Certificate cert = generateSelfSignedCertificate("C=FI, O=FI-TEST, CN=1234567-8", keyPair);
        FISubjectClientIdDecoder.getSubjectClientId(cert);
    }

    X509Certificate generateSelfSignedCertificate(String dn, KeyPair pair) throws OperatorCreationException, CertificateException {
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
        X500Name name = new X500Name(dn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.ONE,
                new Date(),
                new Date(),
                name,
                pair.getPublic()
        );
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
