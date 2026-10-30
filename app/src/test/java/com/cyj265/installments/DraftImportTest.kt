package com.cyj265.installments

import com.cyj265.installments.data.DraftImport
import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.domain.FindingLevel
import com.cyj265.installments.domain.LoanAssembler
import com.cyj265.installments.domain.LoanValidator
import com.cyj265.installments.domain.hasError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DraftImportTest {

    private fun drafts(text: String): List<InstallmentDraft> {
        val result = DraftImport.parse(text)
        assertTrue("应为成功，实际 $result", result is DraftImport.Result.Ok)
        return (result as DraftImport.Result.Ok).drafts
    }

    private fun failure(text: String): String {
        val result = DraftImport.parse(text)
        assertTrue("应为失败：$text", result is DraftImport.Result.Failed)
        return (result as DraftImport.Result.Failed).message
    }

    @Test fun readsChineseKeys() {
        val d = drafts(
            """{"机构":"蚂蚁花呗","单号":"****3271","总期数":12,"当前期":5,"每期金额":"680.00","未还本金":"4,760.00","下期还款日":"2026-10-28"}"""
        ).first()
        assertEquals("蚂蚁花呗", d.institution)
        assertEquals(12, d.totalPeriods)
        assertEquals(5, d.currentPeriod)
        assertEquals("680.00", d.perPeriodAmount)
        assertEquals("4,760.00", d.principalOutstanding)
        assertEquals(28, d.dueDayOfMonth)
    }

    // 卡分期页面的三个日期各是一个字段：交易日期、账单日、还款日，后两个只取日号
    @Test fun readsStatementDayAndTradeDateApartFromDueDay() {
        val d = drafts(
            """{"机构":"交通银行","分期付款交易日期":"2026.08.25","账单日":"15日","还款日":"15日","分期期数":3}"""
        ).first()
        assertEquals("2026-08-25", d.tradeDate)
        assertEquals(LocalDate.of(2026, 8, 25), d.tradeDateParsed)
        assertEquals(15, d.statementDay)
        assertEquals(15, d.dueDayOfMonth)
    }

    // 「账单日」有的页面写整条日期，日号要取最后一段，不能被 2026 这种前缀带偏
    @Test fun statementDayAcceptsAFullDate() {
        assertEquals(15, drafts("""{"机构":"交通银行","账单日":"2026-08-15"}""").first().statementDay)
    }

    // 银行分期详情页把「商品金额」和「剩余未摊销金额」并排写，两个都是本金但不是同一个数
    @Test fun readsPrincipalTotalApartFromOutstanding() {
        val d = drafts(
            """{"机构":"某银行信用卡","分期付款序号":"0004","分期期数":3,"已摊销期数":2,"剩余期数":1,""" +
                """"月还款额":"5,333.34","商品金额":"16,000.00","剩余未摊销金额":"5,333.33"}"""
        ).first()
        assertEquals("16,000.00", d.principalTotal)
        assertEquals("5,333.33", d.principalOutstanding)
        assertEquals(3, d.totalPeriods)
        assertEquals(1, d.remainingPeriods)
    }

    // 借呗的页面上没有「X/Y 期」，模型只能交「总期数 + 剩余期数」，减法留给 App
    @Test fun readsChineseRemainingPeriodsAlias() {
        val d = drafts(
            """{"records":[{"机构":"借呗","总期数":6,"剩余期数":2,"每期应还":"2651.67","未还本金":"5169.78","还款日":9}]}"""
        ).first()
        assertEquals(6, d.totalPeriods)
        assertEquals(2, d.remainingPeriods)
        assertNull(d.currentPeriod)
        assertEquals(5, d.currentPeriodOrDerived)
        assertEquals(4, LoanAssembler.fromDraft(d, now = LocalDate.of(2026, 10, 20)).paidCount)
    }

    @Test fun topLevelArrayBecomesMultipleDrafts() {
        val list = drafts("""[{"机构":"花呗"},{"机构":"京东白条","期数":"12期"}]""")
        assertEquals(2, list.size)
        assertEquals("京东白条", list[1].institution)
        assertEquals(12, list[1].totalPeriods)
    }

    @Test fun readsEnvelopeArrayKey() {
        val list = drafts("""{"records":[{"机构":"花呗"},{"机构":"微粒贷"}],"note":"共 2 笔"}""")
        assertEquals(listOf("花呗", "微粒贷"), list.map { it.institution })
    }

    @Test fun stripsCodeFenceAndProse() {
        val d = drafts(
            "根据截图识别结果如下：\n```json\n{\"institution\":\"支付宝花呗\",\"totalPeriods\":12}\n```\n如有出入请手动修正。"
        ).first()
        assertEquals("支付宝花呗", d.institution)
    }

    @Test fun nestedEmptyArrayDoesNotTruncateSegment() {
        val d = drafts("""前置说明 [{"meta":[],"institution":"支付宝花呗","currentPeriod":"第 5 期"}]""").first()
        assertEquals("支付宝花呗", d.institution)
        assertEquals(5, d.currentPeriod)
    }

    @Test fun acceptsNumericAndSymbolicAmounts() {
        val list = drafts("""[{"机构":"A","每期金额":680},{"机构":"B","每期金额":"￥680.00元"}]""")
        assertEquals("680", list[0].perPeriodAmount)
        assertEquals("￥680.00元", list[1].perPeriodAmount)
    }

    @Test fun ignoresNullAndBlankValues() {
        val d = drafts("""{"机构":"花呗","单号":null,"备注":"","总期数":"-"}""").first()
        assertNull(d.loanNo)
        assertNull(d.note)
        assertNull(d.totalPeriods)
    }

    @Test fun paidPeriodsAcceptsStringList() {
        val d = drafts("""{"机构":"花呗","已还期":"1,2、3 4"}""").first()
        assertEquals(listOf(1, 2, 3, 4), d.paidPeriods)
    }

    @Test fun recordWithoutInstitutionIsDropped() {
        val list = drafts("""[{"机构":"花呗","期数":6},{"每期金额":"680"}]""")
        assertEquals(1, list.size)
        assertEquals(6, list.first().totalPeriods)
    }

    // 银行分期页本来就不写平台归属，模型交 institution:null 是照实抄，不是垃圾记录：
    // 整批退回会让人连期数、金额都看不到，只能重新截图。放进来让确认页把机构标红即可。
    @Test fun keepsInstitutionlessRecordWithEnoughNumbers() {
        val d = drafts(
            """{"records":[{"institution":null,"totalPeriods":3,"remainingPeriods":1,"perPeriodAmount":"5413.34",""" +
                """"principalTotal":"16000.00","principalOutstanding":"5333.33","tradeDate":"2026-08-25",""" +
                """"statementDay":null,"dueDayOfMonth":null}]}"""
        ).first()
        assertEquals("", d.institution)
        assertEquals(3, d.totalPeriods)
        assertEquals("5413.34", d.perPeriodAmount)
        assertEquals("2026-08-25", d.tradeDate)
    }

    @Test fun failsReadably() {
        assertTrue(failure("").contains("没找到"))
        assertTrue(failure("这是一段纯文字，没有任何结构").contains("没找到"))
        assertTrue(failure("[{\"机构\":\"花呗\"},{\"机构\":").contains("语法有误"))
        assertTrue(failure("""[{"每期金额":"680"}]""").contains("机构名"))
    }

    @Test fun extractJsonFencesTakePriority() {
        val payload = DraftImport.extractJson("前言 {\"a\":1} 中间 ```json\n{\"institution\":\"花呗\"}\n``` 后语")
        assertNotNull(payload)
        assertTrue(payload!!.contains("花呗"))
    }

    @Test fun assemblesIntoLoanAfterParse() {
        val d = drafts(
            """{"机构":"花呗","单号":"****3271","总期数":12,"当前期":5,"每期金额":"680.00","还款日":28,"下期还款日":"2026-10-28"}"""
        ).first()
        val loan = LoanAssembler.fromDraft(d, LocalDate.of(2026, 10, 20))
        assertEquals(12, loan.periods.size)
        assertEquals(544000L, loan.remainingPayableCents)
    }

    @Test fun readsEnglishPlanArray() {
        val d = drafts(
            """{"institution":"支付宝借呗","totalPeriods":3,"plan":[""" +
                """{"index":1,"dueDate":"2026-10-09","amount":"5413.34","principal":"5333.33","interest":"80.01"},""" +
                """{"index":2,"dueDate":"2026-11-09","amount":"5398.34","principal":"5333.33","interest":"65.01"},""" +
                """{"index":3,"dueDate":"2026-12-09","amount":"5383.34","principal":"5333.34","interest":"50.00"}]}"""
        ).first()
        assertEquals(3, d.plan?.size)
        assertEquals(3, d.completePlan?.size)
        assertEquals("2026-11-09", d.completePlan?.get(1)?.dueDate)
        assertEquals("5398.34", d.completePlan?.get(1)?.amount)
        assertEquals("5333.33", d.completePlan?.get(1)?.principal)
    }

    @Test fun readsChinesePlanAliasesAndNormalizesDates() {
        val d = drafts(
            """{"机构":"借呗","总期数":2,"还款计划":[""" +
                """{"期数":1,"应还日期":"2026年10月9日","应还金额":"￥5,413.34","本金":"5333.33","利息":"80.01"},""" +
                """{"期数":2,"应还日期":"2026年11月9日","应还金额":"￥5,398.34","本金":"5333.33","利息":"65.01"}]}"""
        ).first()
        assertEquals("2026-10-09", d.completePlan?.first()?.dueDate)
        assertEquals("5,413.34", d.completePlan?.first()?.amount?.let { it.replace("￥", "") })
        assertEquals(541334L, com.cyj265.installments.data.Money.parseCents(d.completePlan?.first()?.amount))
    }

    // 半张计划表比没有更危险：按它算会漏期，退回均摊至少和总期数对得上
    @Test fun incompletePlanIsNotAcceptedAsComplete() {
        val d = drafts(
            """{"机构":"借呗","总期数":3,"还款计划":[""" +
                """{"期数":1,"应还日期":"2026-10-09","应还金额":"5413.34"},""" +
                """{"期数":2,"应还日期":"2026-11-09","应还金额":"5398.34"}]}"""
        ).first()
        assertEquals(2, d.plan?.size)
        assertNull(d.completePlan)
    }

    @Test fun planOnlyRecordSurvivesWithoutPerPeriodAmount() {
        val d = drafts(
            """{"records":[{"institution":"支付宝借呗","totalPeriods":2,"plan":[""" +
                """{"index":1,"dueDate":"2026-10-09","amount":"5413.34"},""" +
                """{"index":2,"dueDate":"2026-11-09","amount":"5398.34"}]}]}"""
        ).first()
        assertNotNull(d.completePlan)
        assertNull(d.perPeriodAmount)
    }

    // 真模型输出：甘肃农商 36 期等额本金，机构名是 null、只有 plan。
    // 每期本金 1944.44×35 + 末期 1944.60 正好 70,000，末期又提前到 16 号——
    // 前者说明逐期表是准的，后者不许被当成抄错。
    @Test fun realEqualPrincipalPlanWithNullInstitutionStillImports() {
        val d = drafts(GANSU_36P).first()
        assertTrue(d.institution.isBlank())
        assertEquals(36, d.completePlan?.size)

        val today = LocalDate.of(2026, 10, 29)
        val loan = LoanAssembler.fromDraft(d, now = today)
        assertEquals(36, loan.periods.size)
        assertTrue(loan.amountsVary)
        assertEquals(7_000_000L, loan.planPrincipalCents)
        assertEquals(LocalDate.of(2028, 1, 16), loan.periods.last().dueDate)
        // 10-21 那期已经过了，所以当前期是第 22 期
        assertEquals(22, loan.nextUnpaid?.index)

        // 机构名空着只该报这一条，其余字段都够入库
        val errors = LoanValidator.validateDraft(d, today).filter { it.level == FindingLevel.ERROR }
        assertEquals(listOf("institution"), errors.map { it.field })
    }

    // 真模型输出：交行分期详情只有「每期金额 / 本金 / 利息」，没有应还日这一栏。
    // 整列日期都空的计划要收下：金额和本息照抄，日期按首期 + 每月推；
    // 均摊成 7548.86×6 = 45,293.16 会比原文少 3 分，还把逐期本息丢了。
    @Test fun dateLessPlanKeepsCopiedAmountsAndDerivesDates() {
        val today = LocalDate.of(2026, 10, 29)
        val d = drafts(JIAOHANG_6P).first()
        assertNull(d.completePlan)
        assertEquals(6, d.planWithoutDates?.size)

        // 页面上确实没有账单日和还款日，这两格仍得人填；但不再额外骂一句「计划会被忽略」
        val before = LoanValidator.validateDraft(d, today)
        assertEquals(
            listOf("nextDueDate", "statementDay"),
            before.filter { it.level == FindingLevel.ERROR }.map { it.field },
        )
        assertFalse(before.any { it.field == "plan" })

        val filled = d.copy(statementDay = 15, dueDayOfMonth = 25)
        assertFalse(LoanValidator.validateDraft(filled, today).hasError())
        val loan = LoanAssembler.fromDraft(filled, now = today)
        assertEquals(
            listOf("2026-11-25", "2026-12-25", "2027-01-25", "2027-02-25", "2027-03-25", "2027-04-25"),
            loan.periods.map { it.dueDate.toString() },
        )
        assertEquals(4_529_319L, loan.planTotalCents)
        assertEquals(4_300_000L, loan.planPrincipalCents)
        // 末期 7548.89 是原文里的凑整，走均摊会被抹成 7548.86
        assertEquals(754_889L, loan.periods.last().amountCents)
        assertTrue(loan.periods.none { it.paid })
    }

    // 只缺其中一两期日期是抄漏，不是「页面没这一列」：半张表补不出来，整张丢
    @Test fun planWithOnlySomeDatesMissingIsStillDiscarded() {
        val today = LocalDate.of(2026, 10, 29)
        val d = drafts(JIAOHANG_6P).first()
        val partial = d.copy(
            plan = d.plan!!.mapIndexed { i, p -> if (i == 2) p.copy(dueDate = "2027-01-25") else p },
        )
        assertNull(partial.completePlan)
        assertNull(partial.planWithoutDates)
        assertTrue(LoanValidator.validateDraft(partial, today).any { it.field == "plan" })
    }

    private val JIAOHANG_6P = """
{"records":[{"institution":"交通银行","loanNo":null,"totalPeriods":6,"currentPeriod":1,"remainingPeriods":5,"perPeriodAmount":"7548.86","principalTotal":"43000.00","principalOutstanding":"36098.29","tradeDate":"2026-10-18","statementDay":null,"dueDayOfMonth":null,"nextDueDate":null,"paidPeriods":null,"note":"银行卡：交通银行信用卡 尾号1430；分期利率18.06%","plan":[
{"index":1,"dueDate":null,"amount":"7548.86","principal":"6901.71","interest":"647.15"},
{"index":2,"dueDate":null,"amount":"7548.86","principal":"7005.59","interest":"543.27"},
{"index":3,"dueDate":null,"amount":"7548.86","principal":"7111.02","interest":"437.84"},
{"index":4,"dueDate":null,"amount":"7548.86","principal":"7218.04","interest":"330.82"},
{"index":5,"dueDate":null,"amount":"7548.86","principal":"7326.67","interest":"222.19"},
{"index":6,"dueDate":null,"amount":"7548.89","principal":"7436.97","interest":"111.92"}]}]}
"""

    private val GANSU_36P = """{"records":[{"institution":null,"loanNo":null,"totalPeriods":36,"currentPeriod":null,"remainingPeriods":null,"perPeriodAmount":"2280.95","principalTotal":null,"principalOutstanding":null,"tradeDate":null,"statementDay":null,"dueDayOfMonth":21,"nextDueDate":null,"paidPeriods":null,"note":"还款计划，还款方式等额本金；第36期还款日为2028-01-16，其余期数还款日为当月21日","plan":[
{"index":1,"dueDate":"2025-02-21","amount":"2280.95","principal":"1944.44","interest":"336.51"},
{"index":2,"dueDate":"2025-03-21","amount":"2233.11","principal":"1944.44","interest":"288.67"},
{"index":3,"dueDate":"2025-04-21","amount":"2224.86","principal":"1944.44","interest":"280.42"},
{"index":4,"dueDate":"2025-05-21","amount":"2216.61","principal":"1944.44","interest":"272.17"},
{"index":5,"dueDate":"2025-06-21","amount":"2208.37","principal":"1944.44","interest":"263.93"},
{"index":6,"dueDate":"2025-07-21","amount":"2200.12","principal":"1944.44","interest":"255.68"},
{"index":7,"dueDate":"2025-08-21","amount":"2191.87","principal":"1944.44","interest":"247.43"},
{"index":8,"dueDate":"2025-09-21","amount":"2183.62","principal":"1944.44","interest":"239.18"},
{"index":9,"dueDate":"2025-10-21","amount":"2175.38","principal":"1944.44","interest":"230.94"},
{"index":10,"dueDate":"2025-11-21","amount":"2167.13","principal":"1944.44","interest":"222.69"},
{"index":11,"dueDate":"2025-12-21","amount":"2158.88","principal":"1944.44","interest":"214.44"},
{"index":12,"dueDate":"2026-01-21","amount":"2150.63","principal":"1944.44","interest":"206.19"},
{"index":13,"dueDate":"2026-02-21","amount":"2142.38","principal":"1944.44","interest":"197.94"},
{"index":14,"dueDate":"2026-03-21","amount":"2134.14","principal":"1944.44","interest":"189.70"},
{"index":15,"dueDate":"2026-04-21","amount":"2125.89","principal":"1944.44","interest":"181.45"},
{"index":16,"dueDate":"2026-05-21","amount":"2117.64","principal":"1944.44","interest":"173.20"},
{"index":17,"dueDate":"2026-06-21","amount":"2109.39","principal":"1944.44","interest":"164.95"},
{"index":18,"dueDate":"2026-07-21","amount":"2101.15","principal":"1944.44","interest":"156.71"},
{"index":19,"dueDate":"2026-08-21","amount":"2092.90","principal":"1944.44","interest":"148.46"},
{"index":20,"dueDate":"2026-09-21","amount":"2084.65","principal":"1944.44","interest":"140.21"},
{"index":21,"dueDate":"2026-10-21","amount":"2076.40","principal":"1944.44","interest":"131.96"},
{"index":22,"dueDate":"2026-11-21","amount":"2068.16","principal":"1944.44","interest":"123.72"},
{"index":23,"dueDate":"2026-12-21","amount":"2059.91","principal":"1944.44","interest":"115.47"},
{"index":24,"dueDate":"2027-01-21","amount":"2051.66","principal":"1944.44","interest":"107.22"},
{"index":25,"dueDate":"2027-02-21","amount":"2043.41","principal":"1944.44","interest":"98.97"},
{"index":26,"dueDate":"2027-03-21","amount":"2035.17","principal":"1944.44","interest":"90.73"},
{"index":27,"dueDate":"2027-04-21","amount":"2026.92","principal":"1944.44","interest":"82.48"},
{"index":28,"dueDate":"2027-05-21","amount":"2018.67","principal":"1944.44","interest":"74.23"},
{"index":29,"dueDate":"2027-06-21","amount":"2010.42","principal":"1944.44","interest":"65.98"},
{"index":30,"dueDate":"2027-07-21","amount":"2002.17","principal":"1944.44","interest":"57.73"},
{"index":31,"dueDate":"2027-08-21","amount":"1993.93","principal":"1944.44","interest":"49.49"},
{"index":32,"dueDate":"2027-09-21","amount":"1985.68","principal":"1944.44","interest":"41.24"},
{"index":33,"dueDate":"2027-10-21","amount":"1977.43","principal":"1944.44","interest":"32.99"},
{"index":34,"dueDate":"2027-11-21","amount":"1969.18","principal":"1944.44","interest":"24.74"},
{"index":35,"dueDate":"2027-12-21","amount":"1960.94","principal":"1944.44","interest":"16.50"},
{"index":36,"dueDate":"2028-01-16","amount":"1951.75","principal":"1944.60","interest":"7.15"}]}]}
"""
}
