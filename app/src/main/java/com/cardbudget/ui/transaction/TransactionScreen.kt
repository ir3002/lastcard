package com.cardbudget.ui.transaction

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cardbudget.data.entity.TransactionCategory
import com.cardbudget.data.entity.TransactionEntity
import com.cardbudget.data.entity.TransactionSource
import com.cardbudget.data.repository.CardRepository
import com.cardbudget.data.repository.TransactionRepository
import com.cardbudget.ui.TransactionViewModel
import com.cardbudget.ui.home.TransactionItem
import com.cardbudget.ui.home.toWon
import com.cardbudget.util.SmsImporter
import com.cardbudget.util.SmsImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── SmsImport ViewModel ──────────────────────────────────
sealed class ImportState {
    object Idle : ImportState()
    object Loading : ImportState()
    data class Done(val result: SmsImportResult) : ImportState()
    data class Error(val message: String) : ImportState()
}

@HiltViewModel
class SmsImportViewModel @Inject constructor(
    private val smsImporter: SmsImporter
) : ViewModel() {
    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    fun importSms() {
        viewModelScope.launch {
            _importState.value = ImportState.Loading
            try {
                val result = smsImporter.importFromInbox()
                _importState.value = ImportState.Done(result)
            } catch (e: Exception) {
                _importState.value = ImportState.Error(e.message ?: "오류 발생")
            }
        }
    }

    fun resetState() { _importState.value = ImportState.Idle }
}

// ─── Screen ───────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionScreen(
    viewModel: TransactionViewModel = hiltViewModel(),
    importViewModel: SmsImportViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importState by importViewModel.importState.collectAsStateWithLifecycle()

    // 가져오기 완료 다이얼로그
    if (importState is ImportState.Done) {
        val result = (importState as ImportState.Done).result
        AlertDialog(
            onDismissRequest = { importViewModel.resetState() },
            title = { Text("문자 가져오기 완료") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("카드 문자 ${result.total}건 분석")
                    Text("새로 추가: ${result.imported}건", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("중복 건너뜀: ${result.skipped}건", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(onClick = { importViewModel.resetState() }) { Text("확인") }
            }
        )
    }

    if (importState is ImportState.Error) {
        AlertDialog(
            onDismissRequest = { importViewModel.resetState() },
            title = { Text("오류") },
            text = { Text((importState as ImportState.Error).message) },
            confirmButton = { Button(onClick = { importViewModel.resetState() }) { Text("확인") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("거래 내역") },
                actions = {
                    IconButton(onClick = { viewModel.showAddDialog() }) {
                        Icon(Icons.Default.Add, "거래 추가")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddDialog() }) {
                Icon(Icons.Default.Add, null)
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // SMS 자동수집 배너 + 기존 문자 가져오기 버튼
            Card(
                Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Sms, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("SMS 자동 수집 활성화", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            val autoCount = state.transactions.count { it.source == TransactionSource.SMS_AUTO }
                            Text("이번 달 ${autoCount}건 자동 추가됨", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 기존 문자 가져오기 버튼
                    OutlinedButton(
                        onClick = { importViewModel.importSms() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = importState !is ImportState.Loading
                    ) {
                        if (importState is ImportState.Loading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("문자함 분석 중...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.FileDownload, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("기존 문자함에서 가져오기 (최근 3개월)", fontSize = 13.sp)
                        }
                    }
                }
            }

            // 카드 필터
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = state.selectedCardId == null,
                        onClick = { viewModel.selectCard(null) },
                        label = { Text("전체") }
                    )
                }
                items(state.cards) { card ->
                    FilterChip(
                        selected = state.selectedCardId == card.id,
                        onClick = { viewModel.selectCard(card.id) },
                        label = { Text(card.name) }
                    )
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val total = state.transactions.sumOf { it.amount }
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(Modifier.padding(12.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.yearMonth} 합계", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(total.toWon(), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }

                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(state.transactions, key = { it.id }) { tx ->
                        TransactionItemWithDelete(
                            transaction = tx,
                            cards = state.cards,
                            onDelete = { viewModel.deleteTransaction(tx) }
                        )
                    }
                    if (state.transactions.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Inbox, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("거래 내역이 없습니다", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("위 버튼으로 기존 문자를 가져오거나\n+ 버튼으로 직접 추가하세요", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.showAddDialog) {
            AddTransactionDialog(
                cards = state.cards,
                onDismiss = { viewModel.hideAddDialog() },
                onConfirm = { cardId, merchant, amount, category, memo ->
                    viewModel.addManualTransaction(cardId, merchant, amount, category, memo, System.currentTimeMillis())
                }
            )
        }
    }
}

@Composable
private fun TransactionItemWithDelete(
    transaction: TransactionEntity,
    cards: List<com.cardbudget.data.entity.CardEntity>,
    onDelete: () -> Unit
) {
    var showConfirm by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TransactionItem(transaction = transaction, cards = cards)
        }
        IconButton(onClick = { showConfirm = true }, modifier = Modifier.padding(end = 8.dp)) {
            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.5f), modifier = Modifier.size(18.dp))
        }
    }
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("거래 삭제") },
            text = { Text("${transaction.merchantName} 거래를 삭제할까요?") },
            confirmButton = {
                Button(
                    onClick = { onDelete(); showConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("삭제") }
            },
            dismissButton = { OutlinedButton(onClick = { showConfirm = false }) { Text("취소") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionDialog(
    cards: List<com.cardbudget.data.entity.CardEntity>,
    onDismiss: () -> Unit,
    onConfirm: (Long, String, Long, TransactionCategory, String) -> Unit
) {
    var merchantName by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var selectedCardId by remember { mutableLongStateOf(cards.firstOrNull()?.id ?: 0L) }
    var selectedCategory by remember { mutableStateOf(TransactionCategory.OTHER) }
    var memo by remember { mutableStateOf("") }
    var expandCard by remember { mutableStateOf(false) }
    var expandCategory by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("거래 직접 추가") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = merchantName, onValueChange = { merchantName = it }, label = { Text("가맹점명") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = amountText, onValueChange = { amountText = it.filter { c -> c.isDigit() } }, label = { Text("금액 (원)") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                ExposedDropdownMenuBox(expanded = expandCard, onExpandedChange = { expandCard = it }) {
                    OutlinedTextField(value = cards.find { it.id == selectedCardId }?.name ?: "카드 선택", onValueChange = {}, readOnly = true, label = { Text("카드") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandCard) }, modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = expandCard, onDismissRequest = { expandCard = false }) {
                        cards.forEach { card -> DropdownMenuItem(text = { Text(card.name) }, onClick = { selectedCardId = card.id; expandCard = false }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = expandCategory, onExpandedChange = { expandCategory = it }) {
                    OutlinedTextField(value = "${selectedCategory.emoji} ${selectedCategory.displayName}", onValueChange = {}, readOnly = true, label = { Text("카테고리") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandCategory) }, modifier = Modifier.fillMaxWidth().menuAnchor())
                    ExposedDropdownMenu(expanded = expandCategory, onDismissRequest = { expandCategory = false }) {
                        TransactionCategory.values().forEach { cat -> DropdownMenuItem(text = { Text("${cat.emoji} ${cat.displayName}") }, onClick = { selectedCategory = cat; expandCategory = false }) }
                    }
                }
                OutlinedTextField(value = memo, onValueChange = { memo = it }, label = { Text("메모 (선택)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = {
                val amount = amountText.toLongOrNull() ?: return@Button
                if (merchantName.isBlank() || amount <= 0 || selectedCardId == 0L) return@Button
                onConfirm(selectedCardId, merchantName, amount, selectedCategory, memo)
            }) { Text("추가") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("취소") } }
    )
}
