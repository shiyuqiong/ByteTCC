/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytetcc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.jdbc.RecoveredResource;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.compensable.CompensableBeanFactory;
import org.bytesoft.compensable.CompensableManager;
import org.bytesoft.compensable.archive.CompensableArchive;
import org.bytesoft.compensable.aware.CompensableBeanFactoryAware;
import org.bytesoft.compensable.logging.CompensableLogger;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.recovery.TransactionRecoveryCallback;
import org.bytesoft.transaction.recovery.TransactionRecoveryListener;
import org.bytesoft.transaction.supports.serialize.XAResourceDeserializer;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionRecoveryImpl
		implements TransactionRecovery, TransactionRecoveryListener, CompensableBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(TransactionRecoveryImpl.class);

	private CompensableBeanFactory beanFactory;

	private final Map<TransactionXid, Transaction> recovered = new HashMap<TransactionXid, Transaction>();

	public void onRecovery(Transaction transaction) {
		TransactionContext transactionContext = transaction.getTransactionContext();
		TransactionXid xid = transactionContext.getXid();

		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();
		TransactionXid globalXid = xidFactory.createGlobalXid(xid.getGlobalTransactionId());

		this.recovered.put(globalXid, transaction);
	}

	public void startRecovery() {
		this.fireTransactionStartRecovery();
		this.fireCompensableStartRecovery();
	}

	private void fireTransactionStartRecovery() {
		TransactionRecovery transactionRecovery = this.beanFactory.getTransactionRecovery();
		transactionRecovery.startRecovery();
	}

	private void fireCompensableStartRecovery() {
		final TransactionRepository transactionRepository = this.beanFactory.getCompensableRepository();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		compensableLogger.recover(new TransactionRecoveryCallback() {
			public void recover(TransactionArchive archive) {
				this.recover((org.bytesoft.compensable.archive.TransactionArchive) archive);
			}

			public void recover(org.bytesoft.compensable.archive.TransactionArchive archive) {
				XidFactory transactionXidFactory = beanFactory.getTransactionXidFactory();

				CompensableTransactionImpl transaction = reconstructTransaction(archive);
				TransactionContext transactionContext = transaction.getTransactionContext();
				if (transactionContext.isCompensable()) {
					correctStatusIfNecessary(transaction);

					TransactionXid compensableXid = transactionContext.getXid();
					transactionRepository.putTransaction(compensableXid, transaction);
					transactionRepository.putErrorTransaction(compensableXid, transaction);
				} else {
					TransactionXid compensableXid = transactionContext.getXid();
					TransactionXid transactionXid = transactionXidFactory
							.createGlobalXid(compensableXid.getGlobalTransactionId());
					Transaction tx = recovered.get(transactionXid);
					if (tx != null) {
						tx.setTransactionalExtra(transaction);
						transaction.setTransactionalExtra(tx);
					}
					transactionRepository.putTransaction(compensableXid, transaction);
					transactionRepository.putErrorTransaction(compensableXid, transaction);
				}

			}
		});
	}

	public CompensableTransactionImpl reconstructTransaction(TransactionArchive transactionArchive) {
		XidFactory xidFactory = this.beanFactory.getCompensableXidFactory();

		org.bytesoft.compensable.archive.TransactionArchive archive = (org.bytesoft.compensable.archive.TransactionArchive) transactionArchive;

		TransactionContext transactionContext = new TransactionContext();
		transactionContext.setCompensable(true);
		transactionContext.setCoordinator(archive.isCoordinator());
		// transactionContext.setCompensating(compensating); // TODO
		transactionContext.setRecoveried(true);
		transactionContext.setXid(xidFactory.createGlobalXid(archive.getXid().getGlobalTransactionId()));

		CompensableTransactionImpl transaction = new CompensableTransactionImpl(transactionContext);
		transaction.setBeanFactory(this.beanFactory);
		transaction.setTransactionVote(archive.getVote());
		transaction.setTransactionStatus(archive.getCompensableStatus());

		List<XAResourceArchive> participantList = archive.getRemoteResources();
		for (int i = 0; i < participantList.size(); i++) {
			XAResourceArchive participantArchive = participantList.get(i);
			transaction.getParticipantArchiveList().add(participantArchive);
		}

		List<CompensableArchive> compensableList = archive.getCompensableResourceList();
		for (int i = 0; i < compensableList.size(); i++) {
			CompensableArchive compensableArchive = compensableList.get(i);
			transaction.getCompensableArchiveList().add(compensableArchive);
		}

		return transaction;
	}

	private void correctStatusIfNecessary(Transaction transaction) {
		switch (transaction.getTransactionStatus()) {
		case Status.STATUS_ACTIVE:
		case Status.STATUS_MARKED_ROLLBACK:
		case Status.STATUS_PREPARING:
		case Status.STATUS_UNKNOWN:
			this.recoverStatusIfNecessary(transaction);
			break;
		default:
			// ignore
		}
	}

	private void recoverStatusIfNecessary(Transaction transaction) {
		CompensableTransactionImpl compensable = (CompensableTransactionImpl) transaction;
		List<CompensableArchive> archiveList = compensable.getCompensableArchiveList();

		XAResourceDeserializer resourceDeserializer = this.beanFactory.getResourceDeserializer();
		CompensableLogger compensableLogger = this.beanFactory.getCompensableLogger();

		for (int i = 0; i < archiveList.size(); i++) {
			CompensableArchive archive = archiveList.get(i);

			if (archive.isTried() == false) {
				if (StringUtils.isBlank(archive.getTransactionResourceKey())) {
					logger.warn(
							"There is no valid resource participated in the trying branch transaction, the status of the branch transaction is unknown!");
				} else {
					XAResource xares = resourceDeserializer.deserialize(archive.getTransactionResourceKey());
					if (RecoveredResource.class.isInstance(xares)) {
						RecoveredResource resource = (RecoveredResource) xares;
						try {
							resource.recoverable(archive.getTransactionXid());
							archive.setTried(true);
							transaction.setTransactionStatus(Status.STATUS_COMMITTING); // TODO
							transaction.getTransactionContext().setCompensating(true);
							compensableLogger.updateTransaction(compensable.getTransactionArchive());
						} catch (XAException xaex) {
							switch (xaex.errorCode) {
							case XAException.XAER_NOTA:
								transaction.setTransactionStatus(Status.STATUS_MARKED_ROLLBACK);
								compensableLogger.updateTransaction(compensable.getTransactionArchive());
								break;
							case XAException.XAER_RMERR:
								logger.warn(
										"The database table 'bytejta' cannot found, the status of the trying branch transaction is unknown!");
								break;
							case XAException.XAER_RMFAIL:
								Xid xid = archive.getTransactionXid();
								logger.error("Error occurred while recovering the branch transaction service: {}",
										ByteUtils.byteArrayToString(xid.getGlobalTransactionId()), xaex);
								break;
							default:
								logger.error("Illegal state, the status of the trying branch transaction is unknown!");
							}
						}
					} else {
						logger.error("Illegal resources, the status of the trying branch transaction is unknown!");
					}
				}
			} else {
				switch (transaction.getTransactionStatus()) {
				case Status.STATUS_ACTIVE:
				case Status.STATUS_MARKED_ROLLBACK:
				case Status.STATUS_PREPARING:
				case Status.STATUS_ROLLING_BACK:
				case Status.STATUS_UNKNOWN:
					transaction.setTransactionStatus(Status.STATUS_COMMITTING); // TODO
					transaction.getTransactionContext().setCompensating(true);
					compensableLogger.updateTransaction(compensable.getTransactionArchive());
					break;
				case Status.STATUS_PREPARED:
				case Status.STATUS_COMMITTING:
				case Status.STATUS_COMMITTED:
				case Status.STATUS_ROLLEDBACK:
				default:
					// ignore
				}
			}
		}
	}

	public void timingRecover() {
		TransactionRepository transactionRepository = beanFactory.getCompensableRepository();
		List<Transaction> transactions = transactionRepository.getErrorTransactionList();
		int total = transactions == null ? 0 : transactions.size();
		int value = 0;
		for (int i = 0; transactions != null && i < transactions.size(); i++) {
			Transaction transaction = transactions.get(i);
			TransactionContext transactionContext = transaction.getTransactionContext();
			TransactionXid xid = transactionContext.getXid();
			try {
				this.recoverTransaction(transaction);
				transaction.recoveryForget();
			} catch (CommitRequiredException ex) {
				logger.debug("[{}] recover: branch={}, message= commit-required",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()));
				continue;
			} catch (RollbackRequiredException ex) {
				logger.debug("[{}] recover: branch={}, message= rollback-required",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()));
				continue;
			} catch (SystemException ex) {
				logger.debug("[{}] recover: branch={}, message= {}",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage());
				continue;
			} catch (RuntimeException ex) {
				logger.debug("[{}] recover: branch={}, message= {}",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage());
				continue;
			}
		}
		logger.info("[transaction-recovery] total= {}, success= {}", total, value);
	}

	public synchronized void recoverTransaction(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {
		CompensableManager compensableManager = this.beanFactory.getCompensableManager();
		TransactionContext transactionContext = transaction.getTransactionContext();

		if (transactionContext.isCoordinator()) {
			try {
				compensableManager.associateThread(transaction);
				this.recoverCoordinator(transaction);
			} finally {
				compensableManager.desociateThread();
			}
		} // end-if (coordinator)

	}

	public synchronized void recoverCoordinator(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {

		switch (transaction.getTransactionStatus()) {
		case Status.STATUS_ACTIVE:
		case Status.STATUS_MARKED_ROLLBACK:
		case Status.STATUS_PREPARING:
		case Status.STATUS_ROLLING_BACK:
		case Status.STATUS_UNKNOWN:
			transaction.recoveryRollback();
			break;
		case Status.STATUS_PREPARED:
		case Status.STATUS_COMMITTING:
			transaction.recoveryCommit();
			break;
		case Status.STATUS_COMMITTED:
		case Status.STATUS_ROLLEDBACK:
		default:
			// ignore
		}

	}

	public void setBeanFactory(CompensableBeanFactory tbf) {
		this.beanFactory = tbf;
	}

}
