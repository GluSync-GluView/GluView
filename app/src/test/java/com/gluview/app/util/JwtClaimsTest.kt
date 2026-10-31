package com.gluview.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class JwtClaimsTest {

    private fun token(payloadJson: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"HS256"}""".toByteArray()) + "." +
            enc.encodeToString(payloadJson.toByteArray()) + ".assinatura"
    }

    @Test fun `claim admin true libera o modo admin`() {
        assertTrue(JwtClaims.isAdmin(token("""{"sub":"1","admin":"true"}""")))
    }

    @Test fun `sem a claim, com outro valor ou token invalido nao e admin`() {
        assertFalse(JwtClaims.isAdmin(token("""{"sub":"1","email":"a@exemplo.com"}""")))
        assertFalse(JwtClaims.isAdmin(token("""{"sub":"1","admin":"false"}""")))
        assertFalse(JwtClaims.isAdmin(""))
        assertFalse(JwtClaims.isAdmin(null))
        assertFalse(JwtClaims.isAdmin("nao.e-um-jwt-valido!.x"))
    }
}
