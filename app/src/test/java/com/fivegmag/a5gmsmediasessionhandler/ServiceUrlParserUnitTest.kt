package com.fivegmag.a5gmsmediasessionhandler

import com.fivegmag.a5gmsmediasessionhandler.controller.AfEndpoint
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceUrlParser
import com.fivegmag.a5gmsmediasessionhandler.controller.ServiceUrlReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceUrlParserUnitTest {
    private val base = "https://launch.3gppservices.org/ms/svc-1"

    private fun reason(url: String) = ServiceUrlParser.parse(url).reason

    // TS 26.510 V19.2.0 clause 6.2: prefix (scheme, authority, service, service_id, further path segments) and suffix (query, fragment)
    @Test
    fun theUrlIsDecomposedIntoPrefixAndSuffix() {
        val url = ServiceUrlParser.parse("$base/extra/more?content-id=abc&flag&delivery-protocol=HTTP_2#t=10")
        assertTrue(url.accepted)
        assertEquals("https", url.scheme)
        assertEquals("launch.3gppservices.org", url.authority)
        assertEquals("ms", url.service)
        assertEquals("svc-1", url.serviceId)
        assertEquals(listOf("extra", "more"), url.extraPath)
        assertEquals(listOf("content-id" to "abc", "flag" to null, "delivery-protocol" to "HTTP_2"), url.query)
        assertEquals("t=10", url.fragment)
    }

    @Test
    fun theSuffixPartIsOptional() {
        val url = ServiceUrlParser.parse(base)
        assertTrue(url.query.isEmpty())
        assertNull(url.fragment)
        assertEquals("frag", ServiceUrlParser.parse("$base#frag").fragment)
    }

    // listing 6.2-1: the scheme is http: or https: and the ABNF literals are case-insensitive (RFC 5234 clause 2.3)
    @Test
    fun schemeAndAuthorityAreCaseInsensitive() {
        val url = ServiceUrlParser.parse("HTTP://Launch.3GPPServices.ORG/ms/svc-1")
        assertTrue(url.accepted)
        assertEquals("http", url.scheme)
        assertEquals("launch.3gppservices.org", url.authority)
    }

    // listing 6.2-1: 3gpp-domain is the literal launch.3gppservices.org; there is no port and no user information
    @Test
    fun onlyTheLiteralDomainOfHttpAndHttpsIsA3gppServiceUrl() {
        for (url in listOf(
            "ftp://launch.3gppservices.org/ms/svc-1", "mailto:launch.3gppservices.org/ms/svc-1",
            "https://launch.3gppservices.com/ms/svc-1", "https://evil.launch.3gppservices.org/ms/svc-1",
            "https://launch.3gppservices.org.example.net/ms/svc-1", "https://launch.3gppservices.org:8443/ms/svc-1",
            "https://user@launch.3gppservices.org/ms/svc-1", "https://launch.3gppservices.org", "https://launch.3gppservices.org/",
            "https:launch.3gppservices.org/ms/svc-1", "//launch.3gppservices.org/ms/svc-1", "/ms/svc-1", "not a url", ""
        )) {
            assertEquals(url, ServiceUrlReason.NOT_A_3GPP_SERVICE_URL, reason(url))
        }
    }

    // clause 6.2: the prefix part shall not contain "&" unless appropriately escaped
    @Test
    fun anAmpersandInThePrefixIsOnlyAllowedEscaped() {
        assertEquals("a%26b", ServiceUrlParser.parse("https://launch.3gppservices.org/ms/a%26b").serviceId)
        assertEquals(ServiceUrlReason.NOT_A_3GPP_SERVICE_URL, reason("https://launch.3gppservices.org/ms/a&b"))
        assertEquals(ServiceUrlReason.NOT_A_3GPP_SERVICE_URL, reason("https://launch.3gppservices.org/m&s/svc-1"))
        assertEquals(ServiceUrlReason.NOT_A_3GPP_SERVICE_URL, reason("$base/x&y"))
    }

    // RFC 3986 syntax of the path and the query: no space, well-formed percent-encoding (uchar is read as unreserved or percent-encoded)
    @Test
    fun illegalCharactersAndBadPercentEncodingAreNotA3gppServiceUrl() {
        assertEquals(ServiceUrlReason.NOT_A_3GPP_SERVICE_URL, reason("https://launch.3gppservices.org/ms/svc 1"))
        assertEquals(ServiceUrlReason.NOT_A_3GPP_SERVICE_URL, reason("https://launch.3gppservices.org/ms/svc%zz"))
        assertEquals(ServiceUrlReason.NOT_A_3GPP_SERVICE_URL, reason("$base?a=b c"))
        assertEquals(ServiceUrlReason.NOT_A_3GPP_SERVICE_URL, reason("$base#a#b"))
        assertTrue(ServiceUrlParser.parse("https://launch.3gppservices.org/ms/My_svc-1.2~x").accepted)
    }

    // clause 6.3: a service type that the Media Session Handler does not support gets an error response; the service is still decomposed
    @Test
    fun anotherServiceTypeIsWellFormedButNotSupported() {
        val url = ServiceUrlParser.parse("https://launch.3gppservices.org/generic/abc?x=1")
        assertEquals(ServiceUrlReason.UNSUPPORTED_SERVICE_TYPE, url.reason)
        assertFalse(url.accepted)
        assertEquals("generic", url.service)
        assertEquals("abc", url.serviceId)
        assertEquals(ServiceUrlReason.UNSUPPORTED_SERVICE_TYPE, reason("https://launch.3gppservices.org/unknownservice/abc"))
    }

    // TS 26.512 V19.3.0 table 12.4-1: service_id has cardinality 1 for ms (the ABNF of TS 26.510 listing 6.2-1 makes it optional)
    @Test
    fun theServiceIdentifierOfMsIsRequired() {
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("https://launch.3gppservices.org/ms"))
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("https://launch.3gppservices.org/ms/"))
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("https://launch.3gppservices.org/ms/?af-host-address=af.example.net"))
    }

    // clause 4.8.3.2 step 1: the af-host-address values are the candidates, a missing port is 80 (HTTP) or 443 (HTTPS)
    @Test
    fun afHostAddressSelectsTheM5Endpoint() {
        val url = ServiceUrlParser.parse("$base?af-host-address=a1.example.net:8443&af-host-address=a2.example.net")
        assertEquals(listOf(AfEndpoint("a1.example.net", 8443), AfEndpoint("a2.example.net", 443)), url.afCandidates)
        assertEquals(
            "https://a1.example.net:8443/3gpp-maf-session-handling/v1/service-access-information/svc-1",
            url.serviceAccessInformationUrl
        )
        val http = ServiceUrlParser.parse("http://launch.3gppservices.org/ms/svc-1?af-host-address=af.example.net")
        assertEquals(listOf(AfEndpoint("af.example.net", 80)), http.afCandidates)
        assertEquals("http://af.example.net/3gpp-maf-session-handling/v1/service-access-information/svc-1", http.serviceAccessInformationUrl)
    }

    // clause 4.8.3.2 step 2: without af-host-address the default host ms.af.3gppservices.org with port 80 (HTTP) or 443 (HTTPS)
    @Test
    fun withoutAfHostAddressTheDefaultEndpointIsUsed() {
        assertEquals(listOf(AfEndpoint("ms.af.3gppservices.org", 443)), ServiceUrlParser.parse(base).afCandidates)
        assertEquals(listOf(AfEndpoint("ms.af.3gppservices.org", 80)), ServiceUrlParser.parse("http://launch.3gppservices.org/ms/svc-1").afCandidates)
    }

    // table 12.4-1: af-host-address has the format hostname[:port]
    @Test
    fun aMalformedAfHostAddressIsAnError() {
        for (value in listOf("", "af.example.net:", "af.example.net:0", "af.example.net:65536", "af.example.net:80a", "https%3A%2F%2Faf.example.net", "-af.example.net", "a%20b")) {
            assertEquals(value, ServiceUrlReason.INVALID_PARAMETERS, reason("$base?af-host-address=$value"))
        }
    }

    // TS 26.510 clause 9.2.2: {apiRoot}/3gpp-maf-session-handling/v1/service-access-information/{externalServiceId}, one path element, no fragment
    @Test
    fun theServiceAccessInformationRequestEncodesTheServiceIdentifierAsOnePathElement() {
        val url = ServiceUrlParser.parse("https://launch.3gppservices.org/ms/a%2Fb?af-host-address=af.example.net#frag")
        assertEquals("a%2Fb", url.serviceId)
        assertEquals("https://af.example.net/3gpp-maf-session-handling/v1/service-access-information/a%2Fb", url.serviceAccessInformationUrl)
        assertEquals(
            "https://af.example.net/3gpp-maf-session-handling/v1/service-access-information/svc-1",
            ServiceUrlParser.parse("$base?af-host-address=af.example.net#frag").serviceAccessInformationUrl
        )
    }

    // table 12.4-1: values are percent-decoded; media-entry-point is a fully qualified URL, access-token is passed through
    @Test
    fun launchParametersArePercentDecoded() {
        val url = ServiceUrlParser.parse("$base?media-entry-point=https%3A%2F%2Fas.example.net%2Fm.mpd&access-token=ab%2Bcd%3D%3D&content-id=movie-42&multipath-mode=true&client-data-reporting-mode=cmcd-query")
        val launch = url.launch!!
        assertEquals("https://as.example.net/m.mpd", launch.mediaEntryPoint)
        assertEquals("ab+cd==", launch.accessToken)
        assertEquals("movie-42", launch.contentId)
        assertEquals("true", launch.multipathMode)
        assertEquals("cmcd-query", launch.clientDataReportingMode)
        assertNull(launch.serviceOperationPoint)
    }

    // table 12.4-1: delivery-protocol, content-type and profile may occur several times; the order of delivery-protocol is the preference
    @Test
    fun repeatedParametersKeepTheirOrder() {
        val launch = ServiceUrlParser.parse("$base?delivery-protocol=HTTP_3&delivery-protocol=HTTP_2&delivery-protocol=HTTP_1_1&content-type=application%2Fdash%2Bxml&content-type=video%2Fmp4&profile=urn%3Ampeg%3Adash%3Aprofile%3Aisoff-live%3A2011").launch!!
        assertEquals(listOf("HTTP_3", "HTTP_2", "HTTP_1_1"), launch.deliveryProtocols)
        assertEquals(listOf("application/dash+xml", "video/mp4"), launch.contentTypes)
        assertEquals(listOf("urn:mpeg:dash:profile:isoff-live:2011"), launch.profiles)
    }

    // clause 4.8.3.3 step 3: estimated-volume requires service-operation-point
    @Test
    fun estimatedVolumeNeedsAServiceOperationPoint() {
        val launch = ServiceUrlParser.parse("$base?service-operation-point=HD_Premium&estimated-volume=5000000").launch!!
        assertEquals("HD_Premium", launch.serviceOperationPoint)
        assertEquals("5000000", launch.estimatedVolume)
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?estimated-volume=5000000"))
    }

    // table 12.4-1: content-type and profile are an error if media-entry-point is specified
    @Test
    fun clientSideFilteringExcludesAMediaEntryPoint() {
        val mediaEntryPoint = "media-entry-point=https%3A%2F%2Fas.example.net%2Fm.mpd"
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?$mediaEntryPoint&content-type=video%2Fmp4"))
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?$mediaEntryPoint&profile=urn%3Aexample%3Ap"))
    }

    // table 12.4-1: the client data reporting mode is one of cmcd-headers and cmcd-query; more than one mode is an error
    @Test
    fun theClientDataReportingModeIsOneOfTheEnumeration() {
        assertEquals("cmcd-headers", ServiceUrlParser.parse("$base?client-data-reporting-mode=cmcd-headers").launch!!.clientDataReportingMode)
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?client-data-reporting-mode=cmcd-headers&client-data-reporting-mode=cmcd-query"))
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?client-data-reporting-mode=cmcd-json"))
    }

    // table 12.4-1: media-entry-point is a fully qualified URL
    @Test
    fun aMediaEntryPointThatIsNotAnAbsoluteUrlIsAnError() {
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?media-entry-point=%2Frelative%2Fm.mpd"))
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?media-entry-point=as.example.net%2Fm.mpd"))
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?media-entry-point="))
    }

    // clause 4.8.3.3: URL query parameters not specified in clause 12.4 may be ignored
    @Test
    fun unknownParametersAreIgnoredButKeptInTheQuery() {
        val url = ServiceUrlParser.parse("$base?x-vendor-param=1&x-flag")
        assertTrue(url.accepted)
        assertEquals(listOf("x-vendor-param" to "1", "x-flag" to null), url.query)
    }

    // table 12.4-1 cardinality 0..1: a second occurrence is an error (the text states no other reaction)
    @Test
    fun aSingleOccurrenceParameterRepeatedIsAnError() {
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?content-id=a&content-id=b"))
        assertEquals(ServiceUrlReason.INVALID_PARAMETERS, reason("$base?access-token=a&access-token=a"))
    }

    // the parameters of the ms service are not examined for another service type
    @Test
    fun parametersOfMsAreNotCheckedForOtherServiceTypes() {
        assertEquals(ServiceUrlReason.UNSUPPORTED_SERVICE_TYPE, reason("https://launch.3gppservices.org/generic/abc?estimated-volume=1"))
    }

    // clause 4.8.3.2: the launch needs the external service identifier and the API root of the M5 requests of the first Media AF endpoint
    @Test
    fun theLaunchStepGetsTheServiceIdentifierAndTheM5ApiRoot() {
        val url = ServiceUrlParser.parse("https://launch.3gppservices.org/ms/a%2Fb?af-host-address=af.example.net:8443&af-host-address=other.example.net")
        assertEquals("a/b", url.externalServiceId)
        assertEquals("https://af.example.net:8443/3gpp-maf-session-handling/v1/", url.m5BaseUrl)
        assertEquals("http://ms.af.3gppservices.org/3gpp-maf-session-handling/v1/", ServiceUrlParser.parse("http://launch.3gppservices.org/ms/svc-1").m5BaseUrl)
        assertNull(ServiceUrlParser.parse("https://launch.3gppservices.org/generic/svc-1").m5BaseUrl)
        assertNull(ServiceUrlParser.parse("https://launch.3gppservices.org/generic/svc-1").externalServiceId)
    }
}
