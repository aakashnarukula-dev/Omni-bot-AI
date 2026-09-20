package com.gyftalala.omni.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gyftalala.omni.data.Memory

const val WALLET_CARD_ASPECT = 1.586f

@Composable fun CompactWalletCard(memory: Memory, previews: ImagePreviews, modifier: Modifier = Modifier, headerModifier: Modifier = Modifier) {
    val finish = CardPalette.forMemory(memory)
    Box(modifier.fillMaxWidth().aspectRatio(WALLET_CARD_ASPECT).clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(Color(finish.start), Color(finish.end))))
        .border(1.dp, Color(finish.edge).copy(alpha = .65f), RoundedCornerShape(18.dp))) {
        val identity = memory.identity
        if (identity != null) {
            memory.files.firstOrNull { it.mime.startsWith("image/") }?.let {
                VaultImage(it, previews, Modifier.fillMaxSize(), tag = "wallet-id-image-${it.id}", thumbnail = true)
            }
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .35f), Color.Black.copy(alpha = .15f), Color.Black.copy(alpha = .88f)))))
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(identity.kind.label, color = Paper, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
                Column {
                    Text(identity.name.ifBlank { "Name not read" }, color = Paper, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(identity.number.ifBlank { "Number not read" }, color = Paper, fontFamily = FontFamily.Monospace, fontSize = 17.sp, lineHeight = 22.sp, maxLines = 1)
                    if (identity.birthDate.isNotBlank()) Text("Born ${identity.birthDate}", color = Body, fontSize = 10.sp)
                }
            }
        } else {
            val card = memory.card ?: com.gyftalala.omni.data.CardDetails()
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).then(headerModifier), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    CardChip(Modifier.size(34.dp, 26.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(card.type.removeSuffix(" card").uppercase(), color = Paper, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.4.sp, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(Paper.copy(alpha = .1f)).border(.5.dp, Paper.copy(alpha = .2f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
                        Text(card.issuer.ifBlank { memory.title }.uppercase(), color = Body, fontSize = 10.sp, lineHeight = 14.sp,
                            fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Column {
                    MicroLabel("CARD NUMBER")
                    Text(card.number.chunked(4).joinToString(" ").ifBlank { "Number not read" }, color = Paper,
                        fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = if (card.number.length > 16) 1.sp else 2.sp,
                        maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                        CardField("HOLDER", card.holder.ifBlank { "Not read" }, Modifier.weight(1f))
                        CardField("EXPIRY", card.expiry.ifBlank { "—" }, mono = true)
                        CardField("CVV", card.cvv.ifBlank { "—" }, mono = true)
                        if (card.network.isNotBlank()) Column(horizontalAlignment = Alignment.End) {
                            MicroLabel("TYPE")
                            Text(card.network.uppercase(), color = Paper, fontSize = 12.sp, lineHeight = 16.sp, fontStyle = FontStyle.Italic,
                                maxLines = 1, modifier = Modifier.widthIn(max = 54.dp).padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MicroLabel(label: String) {
    Text(label, color = Body.copy(alpha = .8f), fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.sp)
}

@Composable private fun CardField(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Column(modifier) {
        MicroLabel(label)
        Text(value, color = Paper, fontSize = if (mono) 11.sp else 12.sp, lineHeight = 16.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}
