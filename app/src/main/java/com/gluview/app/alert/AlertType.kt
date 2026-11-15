package com.gluview.app.alert

// PUSHOVER = alerta enviado pelo GluSync via Pushover (não gera canal Android — é do servidor).
// DND = furar o Não Perturbe (canal que ignora o DND). DND_VIBRATE = com o Não Perturbe ligado e a
// faixa respeitando o DND, só vibrar em vez de ficar mudo (PT-5, ver DndPolicy).
enum class AlertType { NOTIFICATION, ALARM, DND, PUSHOVER, DND_VIBRATE }
