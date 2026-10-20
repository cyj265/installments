package com.cyj265.installments

import com.cyj265.installments.data.DraftImport
import com.cyj265.installments.data.InstallmentDraft
import com.cyj265.installments.domain.LoanAssembler
import org.junit.Assert.assertEquals
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
}
