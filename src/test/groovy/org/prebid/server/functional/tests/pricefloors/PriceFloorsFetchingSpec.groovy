package org.prebid.server.functional.tests.pricefloors

import org.prebid.server.functional.model.config.PriceFloorsFetch
import org.prebid.server.functional.model.db.StoredRequest
import org.prebid.server.functional.model.mock.services.floorsprovider.PriceFloorRules
import org.prebid.server.functional.model.pricefloors.ModelGroup
import org.prebid.server.functional.model.pricefloors.Rule
import org.prebid.server.functional.model.request.amp.AmpRequest
import org.prebid.server.functional.model.request.auction.BidRequest
import org.prebid.server.functional.model.request.auction.ExtPrebidFloors
import org.prebid.server.functional.model.request.auction.PrebidStoredRequest
import org.prebid.server.functional.model.response.auction.BidResponse
import org.prebid.server.functional.util.PBSUtils

import java.time.Instant

import static org.mockserver.model.HttpStatusCode.BAD_REQUEST_400
import static org.prebid.server.functional.model.Currency.USD
import static org.prebid.server.functional.model.pricefloors.Country.MULTIPLE
import static org.prebid.server.functional.model.pricefloors.MediaType.BANNER
import static org.prebid.server.functional.model.request.auction.DistributionChannel.APP
import static org.prebid.server.functional.model.request.auction.FetchStatus.NONE
import static org.prebid.server.functional.model.request.auction.FetchStatus.SUCCESS
import static org.prebid.server.functional.model.request.auction.Location.FETCH
import static org.prebid.server.functional.model.request.auction.Location.REQUEST

class PriceFloorsFetchingSpec extends PriceFloorsBaseSpec {

    private static final int maxEnforceFloorsRate = 100

    private static final int DEFAULT_MAX_AGE_SEC = 600
    private static final int DEFAULT_PERIOD_SEC = 300
    private static final int MIN_TIMEOUT_MS = 10
    private static final int MAX_TIMEOUT_MS = 10000

    private static final Closure<String> INVALID_CONFIG_METRIC = { account -> "alerts.account_config.${account}.price-floors" }
    private static final String FETCH_FAILURE_METRIC = "price-floors.fetch.failure"

    def "PBS should activate floors feature when price-floors.enabled = true in PBS config"() {
        given: "Pbs with PF configuration"
        def pbsService = pbsServiceFactory.getService(floorsConfig + ["price-floors.enabled": "true"])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch and fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id)
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(pbsService, bidRequest)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data"
        assert floorsProvider.getRequestCount(bidRequest.app.publisher.id) == 1

        and: "PBS should signal bids"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor
    }

    def "PBS should not activate floors feature when price-floors.enabled = false in #description config"() {
        given: "Pbs with PF configuration"
        def pbsService = pbsServiceFactory.getService(floorsConfig + ["price-floors.enabled": pbdConfigEnabled])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch and fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.enabled = accountConfigEnabled
        }
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(pbsService, bidRequest)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should no fetching, no signaling, no enforcing"
        assert floorsProvider.getRequestCount(bidRequest.app.publisher.id) == 0
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert !bidderRequest.imp[0].bidFloor

        where:
        description | pbdConfigEnabled | accountConfigEnabled
        "PBS"       | "false"          | true
        "account"   | "true"           | false
    }

    def "PBS should validate fetch.url from account config"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, without fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.fetch.url = null
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should log error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, bidRequest.site.publisher.id)
        assert floorsLogs.size() == 1
        assert floorsLogs.first().contains("Malformed fetch.url: 'null', passed for account $bidRequest.site.publisher.id")

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid.isEmpty()

        and: "PBS should fall back to the startup configuration"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        assert bidderRequest.imp[0].bidFloor == bidRequest.imp[0].bidFloor
    }

    def "PBS should validate fetch.max-age-sec from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, maxAgeSec in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.fetch.maxAgeSec = DEFAULT_MAX_AGE_SEC - 1
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.app.publisher.id) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid.isEmpty()
    }

    def "PBS should validate fetch.period-sec from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, periodSec in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.fetch = fetchConfig(DEFAULT_PERIOD_SEC,
                    defaultAccountConfigSettings.auction.priceFloors.fetch.maxAgeSec)
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.app.publisher.id) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        fetchConfig << [{ int minPeriodSec, int maxAgeSec -> new PriceFloorsFetch(periodSec: minPeriodSec - 1) },
                        { int minPeriodSec, int maxAgeSec -> new PriceFloorsFetch(periodSec: maxAgeSec + 1) }]
    }

    def "PBS should validate fetch.max-file-size-kb from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, maxFileSizeKb in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.fetch.maxFileSizeKb = PBSUtils.randomNegativeNumber
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.app.publisher.id) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should validate fetch.max-rules from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, maxRules in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.fetch.maxRules = PBSUtils.randomNegativeNumber
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.app.publisher.id) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should validate fetch.timeout-ms from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, timeoutMs in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.fetch = fetchConfig(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.app.publisher.id) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        fetchConfig << [{ int min, int max -> new PriceFloorsFetch(timeoutMs: min - 1) },
                        { int min, int max -> new PriceFloorsFetch(timeoutMs: max + 1) }]
    }

    def "PBS should validate fetch.enforce-floors-rate from account config"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url, enforceFloorsRate in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.enforceFloorsRate = enforceFloorsRate
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Metric alerts.account_config.ACCOUNT.price-floors  should be update"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()
        assert metrics[INVALID_CONFIG_METRIC(bidRequest.app.publisher.id) as String] == 1

        and: "PBS floors validation failure should not reject the entire auction"
        assert !response.seatbid?.isEmpty()

        where:
        enforceFloorsRate << [PBSUtils.randomNegativeNumber, maxEnforceFloorsRate + 1]
    }

    def "PBS should fetch data from provider when price-floors.fetch.enabled = true in account config"() {
        given: "Default BidRequest with ext.prebid.floors"
        def bidRequest = BidRequest.getDefaultBidRequest(APP).tap {
            ext.prebid.floors = ExtPrebidFloors.extPrebidFloors
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.fetch.enabled = true
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data from floors provider"
        assert floorsProvider.getRequestCount(bidRequest.app.publisher.id) == 1
    }

    def "PBS should process floors from request when price-floors.fetch.enabled = false in account config"() {
        given: "BidRequest with floors"
        def bidRequest = bidRequestWithFloors

        and: "Account with fetch.enabled, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.fetch = fetch
        }
        accountDao.save(account)

        and: "Set bidder response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid.first().price = bidRequest.imp[0].bidFloor
        }
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "PBS should not fetch data from floors provider"
        assert floorsProvider.getRequestCount(bidRequest.site.publisher.id) == 0

        and: "Bidder request should contain bidFloor from request"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        assert bidderRequest.imp[0].bidFloor == bidRequest.imp[0].bidFloor

        where:
        fetch << [new PriceFloorsFetch(enabled: false, url: basicFetchUrl), new PriceFloorsFetch(url: basicFetchUrl)]
    }

    def "PBS should fetch data from provider when use-dynamic-data = true"() {
        given: "Pbs with PF configuration with useDynamicData"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.useDynamicData = pbsConfigUseDynamicData
        }
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["settings.default-account-config": mapper.encode(defaultAccountConfigSettings)])

        and: "Default BidRequest with ext.prebid.floors"
        def bidRequest = BidRequest.getDefaultBidRequest(APP).tap {
            ext.prebid.floors = ExtPrebidFloors.extPrebidFloors
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id).tap {
            config.auction.priceFloors.fetch.enabled = true
            config.auction.priceFloors.useDynamicData = accountUseDynamicData
        }
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.app.publisher.id, floorsResponse)

        when: "PBS cache rules and processes auction request"
        cacheFloorsProviderRules(pbsService, bidRequest, floorValue)

        then: "PBS should fetch data from floors provider"
        assert floorsProvider.getRequestCount(bidRequest.app.publisher.id) == 1

        and: "Bidder request should contain bidFloor from request"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        where:
        pbsConfigUseDynamicData | accountUseDynamicData
        false                   | true
        true                    | true
        true                    | null
        null                    | true
    }

    def "PBS should process floors from request when use-dynamic-data = false"() {
        given: "Pbs with PF configuration with useDynamicData"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.useDynamicData = pbsConfigUseDynamicData
        }
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["settings.default-account-config": mapper.encode(defaultAccountConfigSettings)])

        and: "BidRequest with floors"
        def bidRequest = bidRequestWithFloors

        and: "Account with fetch.enabled, fetch.url, useDynamicData in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.useDynamicData = accountUseDynamicData
        }
        accountDao.save(account)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(pbsService, bidRequest)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should fetch data from floors provider"
        assert floorsProvider.getRequestCount(bidRequest.site.publisher.id) == 1

        and: "Bidder request should contain bidFloor from request"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == bidRequest.imp[0].bidFloor

        where:
        pbsConfigUseDynamicData | accountUseDynamicData
        true                    | false
        false                   | false
        false                   | null
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC metric when Floors Provider return status code != 200"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.app.publisher.id
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response  with status code != 200"
        floorsProvider.setResponse(accountId, BAD_REQUEST_400)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(floorsPbsService, bidRequest)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, basicFetchUrl)
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains("Failed to fetch price floor from provider for fetch.url: " +
                "'$basicFetchUrl$accountId', account = $accountId with a reason : Failed to request for " +
                "account $accountId, provider respond with status 400")

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC metric when Floors Provider return invalid json"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.app.publisher.id
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response with invalid json"
        def invalidJson = "{{}}"
        floorsProvider.setResponse(accountId, invalidJson)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, basicFetchUrl)
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains("Failed to fetch price floor from provider for fetch.url: " +
                "'$basicFetchUrl$accountId', account = $accountId with a reason : Failed to parse price floor " +
                "response for account $accountId, cause: DecodeException: Failed to decode")

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when Floors Provider return empty response body"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.app.publisher.id
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response with invalid json"
        floorsProvider.setResponse(accountId, "")

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, basicFetchUrl + accountId)
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains("Failed to fetch price floor from provider for fetch.url: " +
                "'$basicFetchUrl$accountId', account = $accountId with a reason : Failed to parse price floor " +
                "response for account $accountId, response body can not be empty" as String)

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when Floors Provider response doesn't contain model"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account in the DB"
        def accountId = bidRequest.app.publisher.id
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response without modelGroups"
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups = null
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, basicFetchUrl + accountId)
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains("Failed to fetch price floor from provider for fetch.url: " +
                "'$basicFetchUrl$accountId', account = $accountId with a reason : Price floor rules should contain " +
                "at least one model group " as String)

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when Floors Provider response doesn't contain rule"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account in the DB"
        def accountId = bidRequest.app.publisher.id
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response without rules"
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = null
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, basicFetchUrl + accountId)
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains("Failed to fetch price floor from provider for fetch.url: " +
                "'$basicFetchUrl$accountId', account = $accountId with a reason : Price floor rules values can't " +
                "be null or empty, but were null" as String)

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when Floors Provider response has more than fetch.max-rules"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account in the DB"
        def accountId = bidRequest.app.publisher.id
        def maxRules = 1
        def account = getAccountWithEnabledFetch(accountId).tap {
            config.auction.priceFloors.fetch.maxRules = maxRules
        }
        accountDao.save(account)

        and: "Set Floors Provider response with 2 rules"
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values.put(new Rule(mediaType: BANNER, country: MULTIPLE).rule, 0.7)
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, basicFetchUrl + accountId)
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains("Failed to fetch price floor from provider for fetch.url: " +
                "'$basicFetchUrl$accountId', account = $accountId with a reason : Price floor rules number " +
                "2 exceeded its maximum number $maxRules")

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when fetch request exceeds fetch.timeout-ms"() {
        given: "PBS with minTimeoutMs configuration"
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["price-floors.minTimeoutMs": "1"])

        and: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(pbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account in the DB"
        def accountId = bidRequest.app.publisher.id
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider response with timeout"
        floorsProvider.setResponseWithTimeout(accountId)

        when: "PBS processes auction request"
        def response = pbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = pbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = pbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, basicFetchUrl)
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains("Fetch price floor request timeout for fetch.url: '$basicFetchUrl$accountId', " +
                "account $accountId exceeded")

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should log error and increase #FETCH_FAILURE_METRIC when Floors Provider's response size is more than fetch.max-file-size-kb"() {
        given: "Test start time"
        def startTime = Instant.now()

        and: "Flush metrics"
        flushMetrics(floorsPbsService)

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with maxFileSizeKb in the DB"
        def accountId = bidRequest.app.publisher.id
        def maxSize = PBSUtils.getRandomNumber(0, 10)
        def account = getAccountWithEnabledFetch(accountId).tap {
            config.auction.priceFloors.fetch.maxFileSizeKb = maxSize
        }
        accountDao.save(account)

        and: "Set Floors Provider response with Content-Length"
        def floorsResponse = PriceFloorRules.priceFloorRules
        def responseSize = convertKilobyteSizeToByte(maxSize) + 100
        floorsProvider.setResponse(accountId, floorsResponse, ["Content-Length": responseSize as String])

        when: "PBS processes auction request"
        def response = floorsPbsService.sendAuctionRequest(bidRequest)

        and: "PBS processes collected metrics request"
        def metrics = floorsPbsService.sendCollectedMetricsRequest()

        then: "#FETCH_FAILURE_METRIC should be update"
        assert metrics[FETCH_FAILURE_METRIC] == 1

        and: "PBS log should contain error"
        def logs = floorsPbsService.getLogsByTime(startTime)
        def floorsLogs = getLogsByText(logs, basicFetchUrl)
        assert floorsLogs.size() == 1
        assert floorsLogs[0].contains("Failed to fetch price floor from provider for fetch.url: " +
                "'$basicFetchUrl$accountId', account = $accountId with a reason : Response size " +
                "$responseSize exceeded ${convertKilobyteSizeToByte(maxSize)} bytes limit")

        and: "Floors validation failure cannot reject the entire auction"
        assert !response.seatbid?.isEmpty()
    }

    def "PBS should prefer data from stored request when request doesn't contain floors data"() {
        given: "Default BidRequest with storedRequest"
        def bidRequest = request.tap {
            ext.prebid.storedRequest = new PrebidStoredRequest(id: PBSUtils.randomNumber)
        }

        and: "Default stored request with floors"
        def storedRequestModel = bidRequestWithFloors

        and: "Save storedRequest into DB"
        def storedRequest = StoredRequest.getDbStoredRequest(bidRequest, storedRequestModel, accountId)
        storedRequestDao.save(storedRequest)

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(accountId).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain floors data from stored request"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        verifyAll(bidderRequest) {
            imp[0].bidFloor == storedRequestModel.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].bidFloorCur == storedRequestModel.ext.prebid.floors.data.modelGroups[0].currency

            imp[0].ext?.prebid?.floors?.floorRule ==
                    storedRequestModel.ext.prebid.floors.data.modelGroups[0].values.keySet()[0]
            imp[0].ext?.prebid?.floors?.floorRuleValue ==
                    storedRequestModel.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].ext?.prebid?.floors?.floorValue ==
                    storedRequestModel.ext.prebid.floors.data.modelGroups[0].values[rule]

            ext?.prebid?.floors?.location == REQUEST
            ext?.prebid?.floors?.fetchStatus == NONE
            ext?.prebid?.floors?.floorMin == storedRequestModel.ext.prebid.floors.floorMin
            ext?.prebid?.floors?.floorProvider == storedRequestModel.ext.prebid.floors.floorProvider
            ext?.prebid?.floors?.data == storedRequestModel.ext.prebid.floors.data
        }

        where:
        request                              | accountId
        BidRequest.defaultBidRequest         | request.site.publisher.id
        BidRequest.getDefaultBidRequest(APP) | request.app.publisher.id
    }

    def "PBS should prefer data from request when fetch is disabled in account config"() {
        given: "Default BidRequest"
        def bidRequest = bidRequestWithFloors

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        and: "Set bidder response"
        def bidResponse = BidResponse.getDefaultBidResponse(bidRequest).tap {
            seatbid.first().bid.first().price = bidRequest.imp[0].bidFloor
        }
        bidder.setResponse(bidRequest.id, bidResponse)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain floors data from request"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        verifyAll(bidderRequest) {
            imp[0].bidFloor == bidRequest.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].bidFloorCur == bidRequest.ext.prebid.floors.data.modelGroups[0].currency

            imp[0].ext?.prebid?.floors?.floorRule ==
                    bidRequest.ext.prebid.floors.data.modelGroups[0].values.keySet()[0]
            imp[0].ext?.prebid?.floors?.floorRuleValue == bidRequest.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].ext?.prebid?.floors?.floorValue == bidRequest.ext.prebid.floors.data.modelGroups[0].values[rule]

            ext?.prebid?.floors?.location == REQUEST
            ext?.prebid?.floors?.fetchStatus == NONE
            ext?.prebid?.floors?.floorMin == bidRequest.ext.prebid.floors.floorMin
            ext?.prebid?.floors?.floorProvider == bidRequest.ext.prebid.floors.floorProvider
            ext?.prebid?.floors?.data == bidRequest.ext.prebid.floors.data
        }
    }

    def "PBS should prefer data from stored request when fetch is disabled in account config for amp request"() {
        given: "Default AmpRequest"
        def ampRequest = AmpRequest.defaultAmpRequest

        and: "Default stored request with floors "
        def ampStoredRequest = storedRequestWithFloors
        def storedRequest = StoredRequest.getDbStoredRequest(ampRequest, ampStoredRequest)
        storedRequestDao.save(storedRequest)

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(ampRequest.account as String).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        when: "PBS processes amp request"
        floorsPbsService.sendAmpRequest(ampRequest)

        then: "Bidder request should contain floors data from request"
        def bidderRequest = bidder.getBidderRequest(ampStoredRequest.id)
        verifyAll(bidderRequest) {
            imp[0].bidFloor == ampStoredRequest.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].bidFloorCur == ampStoredRequest.ext.prebid.floors.data.modelGroups[0].currency

            imp[0].ext?.prebid?.floors?.floorRule ==
                    ampStoredRequest.ext.prebid.floors.data.modelGroups[0].values.keySet()[0]
            imp[0].ext?.prebid?.floors?.floorRuleValue ==
                    ampStoredRequest.ext.prebid.floors.data.modelGroups[0].values[rule]
            imp[0].ext?.prebid?.floors?.floorValue ==
                    ampStoredRequest.ext.prebid.floors.data.modelGroups[0].values[rule]

            ext?.prebid?.floors?.location == REQUEST
            ext?.prebid?.floors?.fetchStatus == NONE
            ext?.prebid?.floors?.floorMin == ampStoredRequest.ext.prebid.floors.floorMin
            ext?.prebid?.floors?.floorProvider == ampStoredRequest.ext.prebid.floors.floorProvider
            ext?.prebid?.floors?.data == ampStoredRequest.ext.prebid.floors.data
        }
    }

    def "PBS should prefer data from floors provider when floors data is defined in both request and stored request"() {
        given: "BidRequest with storedRequest"
        def bidRequest = bidRequestWithFloors.tap {
            ext.prebid.storedRequest = new PrebidStoredRequest(id: PBSUtils.randomNumber)
        }

        and: "Default stored request with floors"
        def storedRequestModel = bidRequestWithFloors

        and: "Save storedRequest into DB"
        def storedRequest = StoredRequest.getDbStoredRequest(bidRequest, storedRequestModel)
        storedRequestDao.save(storedRequest)

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        when: "PBS cache rules and processes auction request"
        cacheFloorsProviderRules(bidRequest, floorValue)

        then: "Bidder request should contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.data.modelGroups[0].currency

            imp[0].ext?.prebid?.floors?.floorRule == floorsResponse.data.modelGroups[0].values.keySet()[0]
            imp[0].ext?.prebid?.floors?.floorRuleValue == floorValue
            imp[0].ext?.prebid?.floors?.floorValue == floorValue

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            ext?.prebid?.floors?.floorMin == floorsResponse.floorMin
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse.data
        }
    }

    def "PBS should prefer data from floors provider when floors data is defined in stored request for amp request"() {
        given: "Default AmpRequest"
        def ampRequest = AmpRequest.defaultAmpRequest

        and: "Default stored request with floors "
        def ampStoredRequest = storedRequestWithFloors
        def storedRequest = StoredRequest.getDbStoredRequest(ampRequest, ampStoredRequest)
        storedRequestDao.save(storedRequest)

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(ampRequest.account as String)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(ampRequest.account as String, floorsResponse)

        when: "PBS cache rules and processes amp request"
        cacheFloorsProviderRules(ampRequest, floorValue)

        then: "Bidder request should contain floors data from floors provider"
        def bidderRequest = bidder.getBidderRequests(ampStoredRequest.id).last()
        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.data.modelGroups[0].currency

            imp[0].ext?.prebid?.floors?.floorRule == floorsResponse.data.modelGroups[0].values.keySet()[0]
            imp[0].ext?.prebid?.floors?.floorRuleValue == floorValue
            imp[0].ext?.prebid?.floors?.floorValue == floorValue

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            ext?.prebid?.floors?.floorMin == floorsResponse.floorMin
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse.data
        }
    }

    def "PBS should periodically fetch floor rules when previous response from floors provider is #description"() {
        given: "PBS with PF configuration with minMaxAgeSec"
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["price-floors.minMaxAgeSec": "3",
                 "price-floors.minPeriodSec": "3"])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id)
        accountDao.save(account)

        and: "Set Floors Provider #description response"
        floorsProvider.setResponse(bidRequest.app.publisher.id, floorsResponse)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "PBS should cache data from data provider"
        assert floorsProvider.getRequestCount(bidRequest.app.publisher.id) == 1

        and: "PBS should periodically fetch data from data provider"
        PBSUtils.waitUntil({ floorsProvider.getRequestCount(bidRequest.app.publisher.id) > 1 }, 7000, 3000)

        where:
        description | floorsResponse
        "valid"     | PriceFloorRules.priceFloorRules
        "invalid"   | PriceFloorRules.priceFloorRules.tap { data.modelGroups = null }
    }

    def "PBS should continue to hold onto previously fetched rules when fetch.enabled = false in account config"() {
        given: "PBS with PF configuration"
        def defaultAccountConfigSettings = defaultAccountConfigSettings.tap {
            auction.priceFloors.fetch.maxAgeSec = 86400
        }
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["settings.default-account-config": mapper.encode(defaultAccountConfigSettings)])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.app.publisher.id)
        accountDao.save(account)

        and: "Set Floors Provider #description response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(bidRequest.app.publisher.id, floorsResponse)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        and: "Account with disabled fetch in the DB"
        account.tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.update(account)

        and: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain previously cached bidFloor"
        assert bidder.getRequestCount(bidRequest.id) == 2
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()

        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.data.modelGroups[0].currency
            imp[0].ext?.prebid?.floors?.floorRule == floorsResponse.data.modelGroups[0].values.keySet()[0]
            imp[0].ext?.prebid?.floors?.floorRuleValue == floorValue
            imp[0].ext?.prebid?.floors?.floorValue == floorValue

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            ext?.prebid?.floors?.floorMin == floorsResponse.floorMin
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse.data
        }
    }

    def "PBS should validate rules from request when modelWeight from request is invalid"() {
        given: "Default BidRequest with floors"
        def floorValue = PBSUtils.randomFloorValue
        def bidRequest = bidRequestWithFloors.tap {
            ext.prebid.floors.data.modelGroups << ModelGroup.modelGroup
            ext.prebid.floors.data.modelGroups.first().values = [(rule): floorValue + 0.1]
            ext.prebid.floors.data.modelGroups.first().modelWeight = invalidModelWeight
            ext.prebid.floors.data.modelGroups.last().values = [(rule): floorValue]
            ext.prebid.floors.data.modelGroups.last().modelWeight = modelWeight
        }

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to valid modelGroup"
        def bidderRequest = bidder.getBidderRequest(bidRequest.id)
        assert bidderRequest.imp[0].bidFloor == floorValue

        where:
        invalidModelWeight << [0, -1, 1000000]
    }

    def "PBS should validate rules from amp request when modelWeight from request is invalid"() {
        given: "Default AmpRequest"
        def ampRequest = AmpRequest.defaultAmpRequest

        and: "Default stored request with floors"
        def floorValue = PBSUtils.randomFloorValue
        def ampStoredRequest = storedRequestWithFloors.tap {
            ext.prebid.floors.data.modelGroups << ModelGroup.modelGroup
            ext.prebid.floors.data.modelGroups.first().values = [(rule): floorValue + 0.1]
            ext.prebid.floors.data.modelGroups.first().modelWeight = invalidModelWeight
            ext.prebid.floors.data.modelGroups.last().values = [(rule): floorValue]
            ext.prebid.floors.data.modelGroups.last().modelWeight = modelWeight
        }
        def storedRequest = StoredRequest.getDbStoredRequest(ampRequest, ampStoredRequest)
        storedRequestDao.save(storedRequest)

        and: "Account with disabled fetch in the DB"
        def account = getAccountWithEnabledFetch(ampRequest.account as String).tap {
            config.auction.priceFloors.fetch.enabled = false
        }
        accountDao.save(account)

        when: "PBS processes auction request"
        floorsPbsService.sendAmpRequest(ampRequest)

        then: "Bidder request bidFloor should correspond to valid modelGroup"
        def bidderRequest = bidder.getBidderRequest(ampStoredRequest.id)
        assert bidderRequest.imp[0].bidFloor == floorValue

        where:
        invalidModelWeight << [0, -1, 1000000]
    }

    def "PBS should not invalidate previously good fetched data when floors provider return invalid data"() {
        given: "PBS with PF configuration with minMaxAgeSec"
        def pbsService = pbsServiceFactory.getService(floorsConfig +
                ["price-floors.minMaxAgeSec": "3",
                 "price-floors.minPeriodSec": "3"])

        and: "Default BidRequest"
        def bidRequest = BidRequest.getDefaultBidRequest(APP)

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.app.publisher.id
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider #description response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS cache rules"
        cacheFloorsProviderRules(pbsService, bidRequest, floorValue)

        and: "Set Floors Provider response  with status code != 200"
        floorsProvider.setResponse(accountId, BAD_REQUEST_400)

        when: "PBS processes auction request"
        pbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request should contain previously cached floor data"
        assert bidder.getRequestCount(bidRequest.id) > 1
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()

        verifyAll(bidderRequest) {
            imp[0].bidFloor == floorValue
            imp[0].bidFloorCur == floorsResponse.data.modelGroups[0].currency
            imp[0].ext?.prebid?.floors?.floorRule == floorsResponse.data.modelGroups[0].values.keySet()[0]
            imp[0].ext?.prebid?.floors?.floorRuleValue == floorValue
            imp[0].ext?.prebid?.floors?.floorValue == floorValue

            ext?.prebid?.floors?.location == FETCH
            ext?.prebid?.floors?.fetchStatus == SUCCESS
            ext?.prebid?.floors?.floorMin == floorsResponse.floorMin
            ext?.prebid?.floors?.floorProvider == floorsResponse.floorProvider

            ext?.prebid?.floors?.skipRate == floorsResponse.skipRate
            ext?.prebid?.floors?.data == floorsResponse.data
        }
    }

    def "PBS should prefer floorMin from request over floorMin from fetched data"() {
        given: "Default BidRequest"
        def floorMin = PBSUtils.randomFloorValue
        def floorMinCur = USD
        def bidRequest = BidRequest.getDefaultBidRequest(APP).tap {
            ext.prebid.floors = new ExtPrebidFloors(floorMin: floorMin, floorMinCur: floorMinCur)
        }

        and: "Account with enabled fetch, fetch.url in the DB"
        def accountId = bidRequest.app.publisher.id
        def account = getAccountWithEnabledFetch(accountId)
        accountDao.save(account)

        and: "Set Floors Provider #description response"
        def floorValue = floorMin - 0.1
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups[0].values = [(rule): floorValue]
            data.modelGroups[0].currency = floorMinCur
            it.floorMin = floorValue
        }
        floorsProvider.setResponse(accountId, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request floorMin should correspond to floorMin from request"
        assert bidder.getRequestCount(bidRequest.id) == 2
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.ext?.prebid?.floors?.floorMin == floorMin
    }

    def "PBS should reject entire ruleset when modelWeight from floors provider is invalid"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups << ModelGroup.modelGroup
            data.modelGroups.first().values = [(rule): floorValue + 0.1]
            data.modelGroups.first().modelWeight = invalidModelWeight
            data.modelGroups.last().values = [(rule): floorValue]
            data.modelGroups.last().modelWeight = modelWeight
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to rule from valid modelGroup"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        where:
        invalidModelWeight << [0, -1, 1000000]
    }

    def "PBS should reject entire ruleset when skipRate from floors provider is invalid"() {
        given: "Default BidRequest"
        def bidRequest = BidRequest.defaultBidRequest

        and: "Account with enabled fetch, fetch.url in the DB"
        def account = getAccountWithEnabledFetch(bidRequest.site.publisher.id)
        accountDao.save(account)

        and: "Set Floors Provider response"
        def floorValue = PBSUtils.randomFloorValue
        def floorsResponse = PriceFloorRules.priceFloorRules.tap {
            data.modelGroups << ModelGroup.modelGroup
            data.modelGroups.first().values = [(rule): floorValue + 0.1]
            data.modelGroups.first().skipRate = invalidSkipRate
            data.modelGroups.last().values = [(rule): floorValue]
            data.modelGroups.last().skipRate = 0
        }
        floorsProvider.setResponse(bidRequest.site.publisher.id, floorsResponse)

        and: "PBS fetch rules from floors provider"
        cacheFloorsProviderRules(bidRequest)

        when: "PBS processes auction request"
        floorsPbsService.sendAuctionRequest(bidRequest)

        then: "Bidder request bidFloor should correspond to rule from valid modelGroup"
        def bidderRequest = bidder.getBidderRequests(bidRequest.id).last()
        assert bidderRequest.imp[0].bidFloor == floorValue

        where:
        invalidSkipRate << [-1, 101]
    }

    static int convertKilobyteSizeToByte(int kilobyteSize) {
        kilobyteSize * 1024
    }
}
