package org.tron.core.db;

import static org.tron.common.runtime.vm.program.InternalTransaction.TrxType.TRX_CONTRACT_CALL_TYPE;
import static org.tron.common.runtime.vm.program.InternalTransaction.TrxType.TRX_CONTRACT_CREATION_TYPE;
import static org.tron.common.runtime.vm.program.InternalTransaction.TrxType.TRX_PRECOMPILED_TYPE;

import java.math.BigInteger;
import org.spongycastle.util.encoders.Hex;
import org.tron.common.runtime.Runtime;
import org.tron.common.runtime.vm.program.InternalTransaction;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.Constant;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.ContractCapsule;
import org.tron.core.capsule.ReceiptCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ReceiptException;
import org.tron.protos.Contract.CreateSmartContract;
import org.tron.protos.Contract.TriggerSmartContract;
import org.tron.protos.Protocol.SmartContract;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

public class TransactionTrace {

  private TransactionCapsule trx;

  private ReceiptCapsule receipt;

  private Manager dbManager;

  private CpuProcessor cpuProcessor;

  private StorageMarket storageMarket;

  AccountCapsule owner;

  private InternalTransaction.TrxType trxType;

  public TransactionCapsule getTrx() {
    return trx;
  }

  public TransactionTrace(TransactionCapsule trx, Manager dbManager) {
    this.trx = trx;
    Transaction.Contract.ContractType contractType = this.trx.getInstance().getRawData()
        .getContract(0).getType();
    switch (contractType.getNumber()) {
      case ContractType.TriggerSmartContract_VALUE:
        trxType = TRX_CONTRACT_CALL_TYPE;
        break;
      case ContractType.CreateSmartContract_VALUE:
        trxType = TRX_CONTRACT_CREATION_TYPE;
        break;
      default:
        trxType = TRX_PRECOMPILED_TYPE;
    }

    //TODO: set bill owner
    receipt = new ReceiptCapsule(Sha256Hash.ZERO_HASH);
    this.dbManager = dbManager;
    this.owner = dbManager.getAccountStore()
        .get(TransactionCapsule.getOwner(trx.getInstance().getRawData().getContract(0)));
    this.receipt = new ReceiptCapsule(Sha256Hash.ZERO_HASH);

    this.cpuProcessor = new CpuProcessor(this.dbManager);
    this.storageMarket = new StorageMarket(this.dbManager);
  }

  private void checkForSmartContract() {

    long maxCpuUsageInUs = trx.getInstance().getRawData().getMaxCpuUsage();
    long maxStorageUsageInByte = trx.getInstance().getRawData().getMaxStorageUsage();
    long value;
    long limitInDrop = trx.getInstance().getRawData().getFeeLimit(); // in drop
    if (TRX_CONTRACT_CREATION_TYPE == trxType) {
      CreateSmartContract contract = ContractCapsule
          .getSmartContractFromTransaction(trx.getInstance());
      SmartContract smartContract = contract.getNewContract();
      // todo modify later
      value = new BigInteger(
          Hex.toHexString(smartContract.getCallValue().toByteArray()), 16).longValue();
    } else if (TRX_CONTRACT_CALL_TYPE == trxType) {
      TriggerSmartContract contract = ContractCapsule
          .getTriggerContractFromTransaction(trx.getInstance());
      // todo modify later
      value = new BigInteger(
          Hex.toHexString(contract.getCallValue().toByteArray()), 16).longValue();
    } else {
      return;
    }
    long balance = owner.getBalance();

    CpuProcessor cpuProcessor = new CpuProcessor(this.dbManager);
    long cpuInUsFromFreeze = cpuProcessor.getAccountLeftCpuInUsFromFreeze(owner);

    checkAccountInputLimitAndMaxWithinBalance(maxCpuUsageInUs, value,
        balance, limitInDrop, cpuInUsFromFreeze, Constant.DROP_PER_CPU_US);
  }

  private boolean checkAccountInputLimitAndMaxWithinBalance(long maxCpuUsageInUs, long value,
      long balance, long limitInDrop, long cpuInUsFromFreeze, long dropPerCpuUs) {

    if (balance < Math.addExact(limitInDrop, value)) {
      // throw
      return false;
    }
    long CpuInUsFromDrop = Math.floorDiv(limitInDrop, dropPerCpuUs);
    long cpuNeedDrop;
    if (CpuInUsFromDrop > cpuInUsFromFreeze) {
      // prior to use freeze, so not include "="
      cpuNeedDrop = maxCpuUsageInUs * dropPerCpuUs;
    } else {
      cpuNeedDrop = 0;
    }

    if (limitInDrop < cpuNeedDrop) {
      // throw
      return false;
    }

    return true;
  }

  //pre transaction check
  public void init() throws ContractValidateException {

    switch (trxType) {
      case TRX_PRECOMPILED_TYPE:
        break;
      case TRX_CONTRACT_CREATION_TYPE:
      case TRX_CONTRACT_CALL_TYPE:
        checkForSmartContract();
        break;
      default:
        break;
    }

  }

  //set bill
  public void setBill(long cpuUseage, long storageUseage) {
    receipt.setCpuUsage(cpuUseage);
    receipt.setStorageDelta(storageUseage);
  }


  private void checkStorage() {
    //TODO if not enough buy some storage auto
    receipt.buyStorage(0);
  }

  public void exec(Runtime runtime) throws ContractExeException, ContractValidateException {
    /**  VM execute  **/
    runtime.init();
    runtime.execute();
    runtime.go();
  }

  /**
   * pay actually bill(include CPU and storage).
   */
  public void pay() {
    receipt.payCpuBill(owner, cpuProcessor, dbManager.getWitnessController().getHeadSlot());
    receipt.payStorageBill(owner, storageMarket);
    dbManager.getAccountStore().put(owner.getAddress().toByteArray(), owner);
  }

  /**
   * checkBill checked if the receipt of the SR is equal to the receipt generated by the TVM.
   */
  public void checkBill() throws ReceiptException {
    ReceiptCapsule srReceipt = new ReceiptCapsule(this.trx.getInstance().getRet(0).getReceipt(),
        this.trx.getTransactionId());

    if (this.receipt.getStorageDelta() != srReceipt.getStorageDelta()) {
      throw new ReceiptException(
          "Check bill exception, storage delta or fee not equal, current storage delta: "
              + this.receipt.getStorageDelta()
              + ", target storage delta: "
              + srReceipt.getStorageDelta());
    }

    long adjustedCpuUsage = Math.abs(this.receipt.getCpuUsage() - this.receipt.getCpuUsage());

    double cpuUsagePercent = adjustedCpuUsage * 1.0 / srReceipt.getCpuUsage() * 100;

    double percentRange = 30;
    if (cpuUsagePercent > percentRange) {
      throw new ReceiptException(
          "Check bill exception, cpu usage or fee not equal(percent <="
              + percentRange
              + "%), current cpu usage: "
              + this.receipt.getCpuUsage()
              + ", target cpu usage: "
              + srReceipt.getCpuUsage()
              + ", cpu usage percent: "
              + cpuUsagePercent);
    }

    this.receipt.setReceipt(ReceiptCapsule.copyReceipt(srReceipt));
  }

}
