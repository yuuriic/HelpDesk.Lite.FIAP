package br.com.helpdesk.service;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Adia um efeito para depois que a transacao atual for confirmada (OBS-02).
 *
 * <p>Metricas de escrita e linhas de auditoria descrevem algo que <b>aconteceu</b>. Emitidas
 * antes do commit, contavam tambem o que foi desfeito -- o perdedor de uma assuncao
 * concorrente (409), uma violacao de constraint no flush. Aqui o efeito so roda no
 * {@code afterCommit}; se a transacao for revertida, ele simplesmente nao acontece.</p>
 *
 * <p>Fora de uma transacao (testes unitarios que chamam o service com {@code new}) o efeito roda
 * na hora: nao ha o que esperar.</p>
 *
 * <p>O {@code afterCommit} roda na mesma thread da requisicao, ainda dentro dos filtros, entao
 * o {@code requestId} e o {@code usuarioId} do MDC continuam na linha de log.</p>
 */
final class AposCommit {

    private AposCommit() {
    }

    static void executar(Runnable efeito) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            efeito.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                efeito.run();
            }
        });
    }
}
