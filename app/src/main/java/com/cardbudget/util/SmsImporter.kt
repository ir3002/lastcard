package com.cardbudget.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.cardbudget.data.entity.TransactionEntity
import com.cardbudget.data.entity.TransactionSource
import com.cardbudget.data.repository.CardRepository
import com.cardbudget.data.repository.TransactionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class SmsImportResult(
    val total: Int,       // 카드 문자 총 개수
    val imported: Int,    // 새로 추가된 개수
    val skipped: Int      // 중복으로 건너뛴 개수
)

@Singleton
class SmsImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cardRepository: CardRepository,
    private val transactionRepository: TransactionRepository
) {
    // 최근 몇 개월치 문자를 가져올지
    private val IMPORT_MONTHS = 3L

    suspend fun importFromInbox(): SmsImportResult = withContext(Dispatchers.IO) {
        val cards = cardRepository.getAllActiveCardsOnce()
        if (cards.isEmpty()) return@withContext SmsImportResult(0, 0, 0)

        var total = 0
        var imported = 0
        var skipped = 0

        // 3개월 전 epoch ms
        val fromDate = System.currentTimeMillis() -
            (IMPORT_MONTHS * 30 * 24 * 60 * 60 * 1000L)

        // SMS inbox 조회
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date")
        val selection = "date > ?"
        val selectionArgs = arrayOf(fromDate.toString())
        val sortOrder = "date DESC"

        val cursor: Cursor? = try {
            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: Exception) {
            null
        }

        cursor?.use { c ->
            val bodyIdx = c.getColumnIndexOrThrow("body")
            val addressIdx = c.getColumnIndexOrThrow("address")
            val dateIdx = c.getColumnIndexOrThrow("date")

            while (c.moveToNext()) {
                val body = c.getString(bodyIdx) ?: continue
                val address = c.getString(addressIdx) ?: ""
                val dateMs = c.getLong(dateIdx)

                // 카드사 문자인지 확인
                val parsed = SmsParser.parse(address, body) ?: continue
                total++

                // 등록된 카드와 매칭
                val matchedCard = cards.firstOrNull { card ->
                    card.issuer.smsKeywords.any { kw -> body.contains(kw) } &&
                        (card.lastFourDigits.isEmpty() ||
                            card.lastFourDigits == parsed.lastFourDigits)
                } ?: continue

                // 중복 확인 (±5분)
                val fiveMin = 5 * 60 * 1000L
                val duplicate = transactionRepository.findDuplicate(
                    merchantName = parsed.merchantName,
                    amount = parsed.amount,
                    from = dateMs - fiveMin,
                    to = dateMs + fiveMin
                )
                if (duplicate != null) {
                    skipped++
                    continue
                }

                // 청구 월 계산
                val txDate = Instant.ofEpochMilli(dateMs)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                val billingMonth = calcBillingMonth(
                    txDate, matchedCard.paymentDay, matchedCard.billingCycleStartDay
                )

                val tx = TransactionEntity(
                    cardId = matchedCard.id,
                    merchantName = parsed.merchantName,
                    amount = parsed.amount,
                    transactionDate = dateMs,
                    billingMonth = billingMonth,
                    source = TransactionSource.SMS_AUTO,
                    category = SmsBroadcastReceiver.guessCategory(parsed.merchantName),
                    rawSmsBody = body
                )
                transactionRepository.insertTransaction(tx)
                imported++
            }
        }

        SmsImportResult(total, imported, skipped)
    }

    private fun calcBillingMonth(date: LocalDate, paymentDay: Int, cycleStartDay: Int): String {
        val billingDate = if (date.dayOfMonth >= cycleStartDay) date else date.minusMonths(1)
        return billingDate.format(DateTimeFormatter.ofPattern("yyyy-MM"))
    }
}
