package com.killercats.servercore.economy;

public enum TransactionType {
    PAY_SENT("&cPayment sent"),
    PAY_RECEIVED("&aPayment received"),
    BANK_DEPOSIT("&eBank deposit"),
    BANK_WITHDRAW("&eBank withdrawal"),
    BANK_INTEREST("&aBank interest"),
    BANK_UPGRADE("&cBank upgrade"),
    BANK_TRANSFER_OUT("&cBank transfer out"),
    BANK_TRANSFER_IN("&aBank transfer in"),
    NOTE_CREATE("&cBanknote created"),
    NOTE_REDEEM("&aBanknote redeemed"),
    SELL("&aItems sold"),
    DAILY("&aDaily reward"),
    PAYDAY("&aPayday"),
    BOUNTY_PLACE("&cBounty placed"),
    BOUNTY_CLAIM("&aBounty claimed"),
    BOUNTY_REFUND("&aBounty refund"),
    LOTTERY_TICKET("&cLottery tickets"),
    LOTTERY_WIN("&6Lottery win"),
    LOTTERY_REFUND("&aLottery refund"),
    AUCTION_FEE("&cAuction listing fee"),
    AUCTION_SALE("&aAuction sale"),
    AUCTION_PURCHASE("&cAuction purchase"),
    ADMIN("&dAdmin adjustment"),
    DISCORD_LINK("&9Discord link reward"),
    ESSENTIALS("&7EssentialsX");

    private final String display;

    TransactionType(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
