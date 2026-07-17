import Testing
@testable import PaywallKit

@Suite struct PaywallKitTests {
    @Test func planLadderOrdersCorrectly() {
        #expect(Plan.free < Plan.platinum)
        #expect(Plan.platinum < Plan.diamond)
    }

    @Test func productIDsMapToPlans() {
        #expect(Plan.plan(forProductID: "com.chessmaster.premium.monthly") == .platinum)
        #expect(Plan.plan(forProductID: "com.chessmaster.premium.yearly") == .platinum)
        #expect(Plan.plan(forProductID: "com.chessmaster.diamond.monthly") == .diamond)
        #expect(Plan.plan(forProductID: "com.chessmaster.diamond.yearly") == .diamond)
        #expect(Plan.plan(forProductID: "com.other.thing") == .free)
    }
}
