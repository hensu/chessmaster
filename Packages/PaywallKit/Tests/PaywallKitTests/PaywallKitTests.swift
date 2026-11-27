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
        // Price-test variants are the same plan as their base product.
        #expect(Plan.plan(forProductID: "com.chessmaster.diamond.yearly.b") == .diamond)
        #expect(Plan.plan(forProductID: "com.chessmaster.premium.monthly.b") == .platinum)
        #expect(Plan.plan(forProductID: "com.chessmaster.diamond.yearlyx") == .free)
        #expect(EntitlementStore.productID("com.chessmaster.diamond.yearly", variant: "b") == "com.chessmaster.diamond.yearly.b")
        #expect(EntitlementStore.productID("com.chessmaster.diamond.yearly", variant: nil) == "com.chessmaster.diamond.yearly")
        #expect(Plan.plan(forProductID: "com.other.thing") == .free)
    }
}
