package com.cardbudget.util

import com.cardbudget.data.entity.CardIssuer

data class ParsedSmsTransaction(
    val merchantName: String,
    val amount: Long,
    val cardIssuer: CardIssuer,
    val lastFourDigits: String,
    val transactionDate: Long,
    val rawBody: String
)

object SmsParser {

    // 취소/제외 문자 필터
    private val excludeKeywords = listOf("취소", "연회비", "이용대금", "명세서", "광고", "만기")

    fun parse(sender: String, body: String): ParsedSmsTransaction? {
        if (excludeKeywords.any { body.contains(it) }) return null

        val issuer = detectIssuer(sender, body) ?: return null
        val amount = extractAmount(body) ?: return null
        if (amount <= 0 || amount > 50_000_000L) return null

        val merchant = extractMerchant(body, issuer) ?: return null
        if (merchant.length < 2) return null

        return ParsedSmsTransaction(
            merchantName = merchant,
            amount = amount,
            cardIssuer = issuer,
            lastFourDigits = extractLastFour(body),
            transactionDate = System.currentTimeMillis(),
            rawBody = body
        )
    }

    private fun detectIssuer(sender: String, body: String): CardIssuer? {
        val text = sender + body
        return CardIssuer.values().firstOrNull { issuer ->
            issuer.smsKeywords.any { text.contains(it) }
        }
    }

    // ─── 금액 추출 ────────────────────────────────────────
    // 가장 먼저 나오는 콤마 있는 금액, 없으면 4자리 이상 숫자
    private fun extractAmount(body: String): Long? {
        val patterns = listOf(
            Regex("""(\d{1,3}(?:,\d{3})+)원"""),
            Regex("""(\d{4,7})원""")
        )
        for (p in patterns) {
            val result = p.find(body)?.groupValues?.get(1)
                ?.replace(",", "")?.toLongOrNull()
            if (result != null && result > 0) return result
        }
        return null
    }

    // ─── 카드번호 마지막 4자리 ────────────────────────────
    private fun extractLastFour(body: String): String {
        return Regex("""[^\d](\d{4})[^\d]""").find(body)?.groupValues?.get(1) ?: "****"
    }

    // ─── 가맹점 추출 (카드사별 실제 문자 형식 기반) ────────
    private fun extractMerchant(body: String, issuer: CardIssuer): String? {
        return when (issuer) {
            CardIssuer.SHINHAN -> parseShinhan(body)
            CardIssuer.KOOKMIN -> parseKookmin(body)
            CardIssuer.SAMSUNG -> parseSamsung(body)
            CardIssuer.HYUNDAI -> parseHyundai(body)
            CardIssuer.LOTTE   -> parseLotte(body)
            CardIssuer.HANA    -> parseHana(body)
            CardIssuer.WOORI   -> parseWoori(body)
            CardIssuer.NH      -> parseNH(body)
            CardIssuer.BC      -> parseBC(body)
            else -> parseGeneric(body)
        }?.trim()?.take(20)?.ifBlank { null }
    }

    // ─── 신한카드 ─────────────────────────────────────────
    // 형식1: [신한카드] 1234 05/29 14:23 스타벅스 6,500원 승인
    // 형식2: 신한카드(1234) 05/29 14:23 승인 스타벅스 6500원
    // 형식3: [Web발신][신한카드]홍길동님 05/29 14:23 스타벅스 일시불 6,500원
    private fun parseShinhan(body: String): String? {
        // 날짜시간 이후 가맹점
        Regex("""\d{2}/\d{2}\s+\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&()\s]{2,20}?)\s+(?:\d|일시불|승인)""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        // 승인 이후 가맹점
        Regex("""승인\s+([가-힣a-zA-Z0-9&]{2,20})\s+\d""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        return parseGeneric(body)
    }

    // ─── 국민카드 ─────────────────────────────────────────
    // 형식1: [Web발신] KB국민카드(1234) 05/29 14:23 이마트 32,400원 승인
    // 형식2: KB국민카드(1234)\n05/29 14:23\n이마트\n32,400원 승인
    // 형식3: [국민카드] 1234 이마트 32,400원 05.29 14:23 승인
    // 형식4: KB국민카드 승인 32,400원 이마트 05/29
    private fun parseKookmin(body: String): String? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 멀티라인: 날짜가 있는 줄 다음에 가맹점 줄
        for (i in lines.indices) {
            if (lines[i].matches(Regex(""".*\d{2}[./]\d{2}.*\d{2}:\d{2}.*"""))) {
                // 같은 줄에 가맹점이 있는 경우
                val afterDateTime = lines[i]
                    .replace(Regex(""".*\d{2}[./]\d{2}\s+\d{2}:\d{2}\s*"""), "").trim()
                if (afterDateTime.isNotBlank() && !afterDateTime.contains(Regex("""[\d,]+원"""))) {
                    return clean(afterDateTime)
                }
                // 다음 줄이 가맹점
                if (i + 1 < lines.size) {
                    val next = lines[i + 1]
                    if (!next.contains(Regex("""[\d,]+원""")) &&
                        !next.contains("승인") && !next.contains("KB") && next.length >= 2) {
                        return clean(next)
                    }
                }
            }
        }

        // 한 줄: 날짜시간 + 가맹점 + 금액
        Regex("""\d{2}[./]\d{2}\s+\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&\s]{2,20}?)\s+[\d,]+원""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }

        // 금액 앞 가맹점
        Regex("""([가-힣a-zA-Z0-9&]{2,15})\s+[\d,]+원\s*승인""")
            .find(body)?.groupValues?.get(1)?.let {
                if (!it.contains("국민") && !it.contains("KB")) return clean(it)
            }

        // 승인 + 금액 + 가맹점
        Regex("""승인\s+[\d,]+원\s+([가-힣a-zA-Z0-9&]{2,20})""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }

        return parseGeneric(body)
    }

    // ─── 삼성카드 ─────────────────────────────────────────
    // 형식: [삼성카드] 6,500원(일시불) 스타벅스 1234 05/29 14:23 승인
    // 형식2: 삼성카드(1234) 05/29 14:23 스타벅스 6,500원 승인
    private fun parseSamsung(body: String): String? {
        // 금액(일시불) 다음 가맹점
        Regex("""[\d,]+원(?:\([^)]+\))?\s+([가-힣a-zA-Z0-9&]{2,20})\s+\d{4}""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        // 날짜시간 다음 가맹점
        Regex("""\d{2}/\d{2}\s+\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&]{2,20})\s+[\d,]+원""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        return parseGeneric(body)
    }

    // ─── 현대카드 ─────────────────────────────────────────
    // 형식: [현대카드] (1234) 14:23 35,000원 GS칼텍스 승인
    // 형식2: 현대카드(1234) 05/29 GS칼텍스 35,000원 승인
    private fun parseHyundai(body: String): String? {
        Regex("""[\d,]+원\s+([가-힣a-zA-Z0-9&]{2,20})\s+승인""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        Regex("""([가-힣a-zA-Z0-9&]{2,20})\s+[\d,]+원\s+승인""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        return parseGeneric(body)
    }

    // ─── 롯데카드 ─────────────────────────────────────────
    // 형식: [롯데카드] 1234 05/29 11,000원 롯데마트 승인
    // 형식2: 롯데(1234)님 05/29 13:00 롯데마트 11,000원 승인
    private fun parseLotte(body: String): String? {
        Regex("""[\d,]+원\s+([가-힣a-zA-Z0-9&]{2,20})\s+승인""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        Regex("""\d{2}/\d{2}\s+\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&]{2,20})\s+[\d,]+원""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        return parseGeneric(body)
    }

    // ─── 하나카드 ─────────────────────────────────────────
    // 카카오 파서 확인 형식:
    // "하나(6*8*)***님 04/06 15:26 씨유판교 일시불/3,500원/누적-4,645원"
    // "[하나카드] 하나(1234) 05/29 14:23 GS칼텍스 58,000원 승인"
    private fun parseHana(body: String): String? {
        // 날짜시간 이후, 금액 이전
        Regex("""\d{2}/\d{2}\s+\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&\s]{2,20}?)\s+(?:일시불|[\d,]+원|승인)""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        return parseGeneric(body)
    }

    // ─── 우리카드 ─────────────────────────────────────────
    // 형식: [우리카드] 1234 05/29 14:23 카카오페이 15,000원 승인
    private fun parseWoori(body: String): String? {
        Regex("""\d{2}/\d{2}\s+\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&]{2,20})\s+[\d,]+원""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        return parseGeneric(body)
    }

    // ─── NH농협 ───────────────────────────────────────────
    // 형식: [NH농협카드] 1234 05/29 14:23 편의점 8,900원 승인
    // 형식2: NH농협카드(1234) 29일 14:23 편의점 8,900원 승인
    private fun parseNH(body: String): String? {
        Regex("""\d{2}[일/]\s*\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&]{2,20})\s+[\d,]+원""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        Regex("""\d{2}/\d{2}\s+\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&]{2,20})\s+[\d,]+원""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        Regex("""([가-힣a-zA-Z0-9&]{2,20})\s+[\d,]+원\s*승인""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        return parseGeneric(body)
    }

    // ─── BC카드 ───────────────────────────────────────────
    private fun parseBC(body: String): String? {
        Regex("""\d{2}/\d{2}\s+\d{2}:\d{2}\s+([가-힣a-zA-Z0-9&]{2,20})\s+[\d,]+원""")
            .find(body)?.groupValues?.get(1)?.let { return clean(it) }
        return parseGeneric(body)
    }

    // ─── 범용 파서 ────────────────────────────────────────
    private fun parseGeneric(body: String): String? {
        // 금액 바로 앞 단어
        Regex("""([가-힣a-zA-Z][가-힣a-zA-Z0-9&\s]{1,18}?)\s+[\d,]+원""")
            .find(body)?.groupValues?.get(1)?.let {
                val c = clean(it)
                if (c.length >= 2 && !isCardIssuerName(c)) return c
            }
        // 승인 앞 단어
        Regex("""([가-힣a-zA-Z][가-힣a-zA-Z0-9&\s]{1,18}?)\s+승인""")
            .find(body)?.groupValues?.get(1)?.let {
                val c = clean(it)
                if (c.length >= 2 && !isCardIssuerName(c)) return c
            }
        // 멀티라인에서 카드사/날짜/금액 줄 제외
        val skipRegex = Regex("""[\d,]+원|\d{2}[./]\d{2}|승인|카드|KB|NH|BC""")
        return body.lines()
            .map { it.trim() }
            .firstOrNull { line ->
                line.length >= 2 && !skipRegex.containsMatchIn(line) &&
                line.any { it in '가'..'힣' || it.isLetter() }
            }?.let { clean(it) }
    }

    private fun isCardIssuerName(s: String): Boolean {
        val names = listOf("신한", "국민", "삼성", "현대", "롯데", "하나", "우리", "농협", "기업", "씨티")
        return names.any { s.contains(it) }
    }

    private fun clean(raw: String): String {
        return raw
            .replace(Regex("""(승인|사용|일시불|\d+개월할부|취소|누적.*)"""), "")
            .replace(Regex("""\d{4}"""), "")
            .replace(Regex("""[[\]()*님]"""), "")
            .trim()
    }

    fun detectIssuerFromKeywords(smsBody: String): CardIssuer? {
        return CardIssuer.values().firstOrNull { issuer ->
            issuer.smsKeywords.any { kw -> smsBody.contains(kw) }
        }
    }
}
