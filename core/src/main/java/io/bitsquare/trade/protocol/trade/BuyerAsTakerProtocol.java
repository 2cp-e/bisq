/*
 * This file is part of Bitsquare.
 *
 * Bitsquare is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bitsquare is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bitsquare. If not, see <http://www.gnu.org/licenses/>.
 */

package io.bitsquare.trade.protocol.trade;

import io.bitsquare.common.taskrunner.TaskRunner;
import io.bitsquare.p2p.MailboxMessage;
import io.bitsquare.p2p.Message;
import io.bitsquare.p2p.MessageHandler;
import io.bitsquare.p2p.Peer;
import io.bitsquare.trade.BuyerAsTakerTrade;
import io.bitsquare.trade.Trade;
import io.bitsquare.trade.protocol.trade.messages.PayoutTxPublishedMessage;
import io.bitsquare.trade.protocol.trade.messages.RequestPublishDepositTxMessage;
import io.bitsquare.trade.protocol.trade.messages.TradeMessage;
import io.bitsquare.trade.protocol.trade.tasks.buyer.CommitPayoutTx;
import io.bitsquare.trade.protocol.trade.tasks.buyer.CreateAndSignPayoutTx;
import io.bitsquare.trade.protocol.trade.tasks.buyer.CreateDepositTxInputs;
import io.bitsquare.trade.protocol.trade.tasks.buyer.ProcessPayoutTxPublishedMessage;
import io.bitsquare.trade.protocol.trade.tasks.buyer.ProcessRequestPublishDepositTxMessage;
import io.bitsquare.trade.protocol.trade.tasks.buyer.SendDepositTxPublishedMessage;
import io.bitsquare.trade.protocol.trade.tasks.buyer.SendFiatTransferStartedMessage;
import io.bitsquare.trade.protocol.trade.tasks.buyer.SendRequestPayDepositMessage;
import io.bitsquare.trade.protocol.trade.tasks.buyer.SignAndPublishDepositTx;
import io.bitsquare.trade.protocol.trade.tasks.buyer.VerifyAndSignContract;
import io.bitsquare.trade.protocol.trade.tasks.taker.BroadcastTakeOfferFeeTx;
import io.bitsquare.trade.protocol.trade.tasks.taker.CreateTakeOfferFeeTx;
import io.bitsquare.trade.protocol.trade.tasks.taker.VerifyOfferFeePayment;
import io.bitsquare.trade.protocol.trade.tasks.taker.VerifyOffererAccount;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.bitsquare.util.Validator.nonEmptyStringOf;

public class BuyerAsTakerProtocol implements TradeProtocol {
    private static final Logger log = LoggerFactory.getLogger(BuyerAsTakerProtocol.class);

    private final BuyerAsTakerTrade trade;
    private final ProcessModel processModel;
    private final MessageHandler messageHandler;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public BuyerAsTakerProtocol(BuyerAsTakerTrade trade) {
        log.debug("New SellerAsTakerProtocol " + this);
        this.trade = trade;
        processModel = trade.getProcessModel();

        messageHandler = this::handleMessage;
        processModel.getMessageService().addMessageHandler(messageHandler);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Public methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void setMailboxMessage(MailboxMessage mailboxMessage) {
        log.debug("setMailboxMessage " + mailboxMessage);
        // Might be called twice, so check that its only processed once
        if (processModel.getMailboxMessage() == null) {
            processModel.setMailboxMessage(mailboxMessage);
            if (mailboxMessage instanceof PayoutTxPublishedMessage) {
                handle((PayoutTxPublishedMessage) mailboxMessage);
            }
        }
    }

    public void takeAvailableOffer() {
        TaskRunner<Trade> taskRunner = new TaskRunner<>(trade,
                () -> log.debug("taskRunner at takeAvailableOffer completed"),
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                CreateTakeOfferFeeTx.class,
                BroadcastTakeOfferFeeTx.class,
                CreateDepositTxInputs.class,
                SendRequestPayDepositMessage.class
        );
        taskRunner.run();
    }

    public void cleanup() {
        log.debug("cleanup " + this);
        processModel.getMessageService().removeMessageHandler(messageHandler);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(RequestPublishDepositTxMessage tradeMessage) {
        processModel.setTradeMessage(tradeMessage);

        TaskRunner<Trade> taskRunner = new TaskRunner<>(trade,
                () -> log.debug("taskRunner at handleRequestPublishDepositTxMessage completed"),
                this::handleTaskRunnerFault);
        taskRunner.addTasks(
                ProcessRequestPublishDepositTxMessage.class,
                VerifyOffererAccount.class,
                VerifyAndSignContract.class,
                SignAndPublishDepositTx.class,
                SendDepositTxPublishedMessage.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Called from UI
    ///////////////////////////////////////////////////////////////////////////////////////////

    // User clicked the "bank transfer started" button
    public void onFiatPaymentStarted() {
        TaskRunner<Trade> taskRunner = new TaskRunner<>(trade,
                () -> log.debug("taskRunner at onFiatPaymentStarted completed"),
                this::handleTaskRunnerFault);
        taskRunner.addTasks(
                VerifyOfferFeePayment.class,
                CreateAndSignPayoutTx.class,
                SendFiatTransferStartedMessage.class
        );
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Incoming message handling
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handle(PayoutTxPublishedMessage tradeMessage) {
        processModel.setTradeMessage(tradeMessage);

        TaskRunner<Trade> taskRunner = new TaskRunner<>(trade,
                () -> {
                    log.debug("taskRunner at handlePayoutTxPublishedMessage completed");
                    // we are done!
                    processModel.onComplete();
                },
                this::handleTaskRunnerFault);

        taskRunner.addTasks(
                ProcessPayoutTxPublishedMessage.class,
                CommitPayoutTx.class);
        taskRunner.run();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Massage dispatcher
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleMessage(Message message, Peer sender) {
        log.trace("handleNewMessage: message = " + message.getClass().getSimpleName() + " from " + sender);
        if (message instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) message;
            nonEmptyStringOf(tradeMessage.tradeId);

            if (tradeMessage.tradeId.equals(processModel.getId())) {
                if (tradeMessage instanceof RequestPublishDepositTxMessage) {
                    handle((RequestPublishDepositTxMessage) tradeMessage);
                }
                else if (tradeMessage instanceof PayoutTxPublishedMessage) {
                    handle((PayoutTxPublishedMessage) tradeMessage);
                }
                else {
                    log.error("Incoming message not supported. " + tradeMessage);
                }
            }
        }
    }

    private void handleTaskRunnerFault(String errorMessage) {
        log.error(errorMessage);
        cleanup();
    }

}
