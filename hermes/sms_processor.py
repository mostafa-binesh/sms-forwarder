#!/usr/bin/env python3
"""
SMS Bank Transaction Parser & Categorizer
Parses raw bank SMS messages from Iranian banks and categorizes transactions.

Input format (from user's bank):
    440170009184098001
    2,250,000-
    1405/4/31-19:09
    مانده:65,739,482

Output: Structured transaction data with categories
"""

import re
import json
import os
from datetime import datetime
from dataclasses import dataclass, asdict
from typing import Optional

# ============================================================
# Transaction Categories with Persian keywords
# ============================================================
CATEGORIES = {
    "salary": {
        "name_fa": "حقوق",
        "name_en": "Salary",
        "icon": "💰",
        "keywords": ["حقوق", "salary", "حقوقی"],
        "min_amount": 10_000_000,  # >10M rial likely salary
    },
    "rent": {
        "name_fa": "اجاره",
        "name_en": "Rent",
        "icon": "🏠",
        "keywords": ["اجاره", "rent", "رهن"],
        "min_amount": 0,
    },
    "utilities": {
        "name_fa": "قبوض",
        "name_en": "Utilities",
        "icon": "💡",
        "keywords": ["آب", "برق", "گاز", "تلفن", "شارژ", "قبوض"],
        "min_amount": 0,
    },
    "food": {
        "name_fa": "خوراک",
        "name_en": "Food",
        "icon": "🍽️",
        "keywords": ["رستوران", "فست‌فود", "سوپرمارکت", "بقالی", "نان"],
        "min_amount": 0,
    },
    "transport": {
        "name_fa": "حمل‌ونقل",
        "name_en": "Transport",
        "icon": "🚗",
        "keywords": ["بنزین", "gas", "taxi", "اسنپ", "تپسی", "پارکینگ"],
        "min_amount": 0,
    },
    "online_shopping": {
        "name_fa": "خرید آنلاین",
        "name_en": "Online Shopping",
        "icon": "🛒",
        "keywords": ["دیجی‌کالا", "digikala", "ترب", "آمازون", "فروشگاه"],
        "min_amount": 0,
    },
    "transfer_in": {
        "name_fa": "انتقال واریزی",
        "name_en": "Transfer In",
        "icon": "📥",
        "keywords": [],
        "min_amount": 0,
    },
    "transfer_out": {
        "name_fa": "انتقال برداشتی",
        "name_en": "Transfer Out",
        "icon": "📤",
        "keywords": [],
        "min_amount": 0,
    },
    "atm": {
        "name_fa": "خودپرداز",
        "name_en": "ATM",
        "icon": "🏧",
        "keywords": ["خودپرداز", "atm"],
        "min_amount": 0,
    },
    "card_to_card": {
        "name_fa": "کارت به کارت",
        "name_en": "Card to Card",
        "icon": "💳",
        "keywords": ["کارت به کارت", "کارت به"],
        "min_amount": 0,
    },
    "installment": {
        "name_fa": "قسط",
        "name_en": "Installment",
        "icon": "📅",
        "keywords": ["قسط", " installment", "وام"],
        "min_amount": 0,
    },
    "unknown": {
        "name_fa": "نامشخص",
        "name_en": "Unknown",
        "icon": "❓",
        "keywords": [],
        "min_amount": 0,
    },
}

# ============================================================
# Data Classes
# ============================================================

@dataclass
class ParsedTransaction:
    card_number: str
    amount: int  # in Rials, positive = credit, negative = debit
    date_jalali: str  # original Jalali date string
    date_formatted: str  # formatted Jalali date
    balance: int  # remaining balance
    is_credit: bool  # True = واریز, False = برداشت
    category: str  # category key
    category_fa: str  # Persian category name
    category_icon: str  # emoji icon
    raw_sms: str  # original SMS text
    parsed_at: str  # when it was parsed


# ============================================================
# Parser
# ============================================================

class BankSmsParser:
    """Parse Iranian bank SMS messages."""

    # Patterns
    CARD_PATTERN = re.compile(r'\b(\d{14,19})\b')
    AMOUNT_PATTERN = re.compile(r'([\d,]+)([+-])')
    BALANCE_PATTERN = re.compile(r'(?:مانده|باقیمانده)[:\s]*([\d,]+)')
    DATE_PATTERN = re.compile(r'(\d{4}/\d{1,2}/\d{1,2}[-\s]\d{1,2}:\d{2})')

    def parse(self, sms_text: str) -> Optional[ParsedTransaction]:
        """Parse a bank SMS message."""
        lines = [line.strip() for line in sms_text.strip().split('\n') if line.strip()]
        if len(lines) < 3:
            return None

        # Extract card number (16 digits)
        card_number = ""
        for line in lines:
            match = self.CARD_PATTERN.search(line)
            if match:
                card_number = match.group(1)
                break

        # Extract amount
        amount = 0
        is_credit = True
        for line in lines:
            match = self.AMOUNT_PATTERN.search(line)
            if match:
                amount_str = match.group(1).replace(',', '')
                amount = int(amount_str)
                is_credit = match.group(2) == '+'
                if not is_credit:
                    amount = -amount
                break

        # Extract balance
        balance = 0
        for line in lines:
            match = self.BALANCE_PATTERN.search(line)
            if match:
                balance = int(match.group(1).replace(',', ''))
                break

        # Extract date
        date_jalali = ""
        for line in lines:
            match = self.DATE_PATTERN.search(line)
            if match:
                date_jalali = match.group(1)
                break

        # Categorize
        category_key = self._categorize(sms_text, amount, is_credit)
        cat = CATEGORIES[category_key]

        return ParsedTransaction(
            card_number=card_number,
            amount=amount,
            date_jalali=date_jalali,
            date_formatted=date_jalali,
            balance=balance,
            is_credit=is_credit,
            category=category_key,
            category_fa=cat["name_fa"],
            category_icon=cat["icon"],
            raw_sms=sms_text,
            parsed_at=datetime.now().isoformat(),
        )

    def _categorize(self, text: str, amount: int, is_credit: bool) -> str:
        """Categorize a transaction based on amount and keywords."""
        text_lower = text.lower()

        for key, cat in CATEGORIES.items():
            if cat["keywords"]:
                for kw in cat["keywords"]:
                    if kw.lower() in text_lower:
                        return key

        # Amount-based heuristics
        if is_credit and amount >= 10_000_000:
            return "salary"

        if is_credit:
            return "transfer_in"
        else:
            return "transfer_out"

    def parse_batch(self, raw_text: str) -> list:
        """Parse multiple transactions from a single SMS."""
        # Some banks send multiple transactions in one SMS
        # Split by card number occurrences
        transactions = []

        # Try to split by card number pattern
        parts = re.split(r'(?=\b\d{16}\b)', raw_text)
        for part in parts:
            part = part.strip()
            if part:
                result = self.parse(part)
                if result:
                    transactions.append(asdict(result))

        # If no splits worked, try as single transaction
        if not transactions:
            result = self.parse(raw_text)
            if result:
                transactions.append(asdict(result))

        return transactions


# ============================================================
# Summary Generator
# ============================================================

class FinancialSummary:
    """Generate financial summaries from parsed transactions."""

    @staticmethod
    def daily_summary(transactions: list) -> dict:
        """Generate a daily summary."""
        total_credit = sum(t["amount"] for t in transactions if t["amount"] > 0)
        total_debit = sum(abs(t["amount"]) for t in transactions if t["amount"] < 0)

        by_category = {}
        for t in transactions:
            cat = t["category_fa"]
            if cat not in by_category:
                by_category[cat] = {"total": 0, "count": 0, "icon": t["category_icon"]}
            by_category[cat]["total"] += abs(t["amount"])
            by_category[cat]["count"] += 1

        return {
            "total_credit": total_credit,
            "total_debit": total_debit,
            "net": total_credit - total_debit,
            "transaction_count": len(transactions),
            "by_category": by_category,
        }

    @staticmethod
    def format_summary_fa(summary: dict) -> str:
        """Format summary in Persian."""
        lines = ["📊 **خلاصه مالی**\n"]

        lines.append(f"💰 واریز: **{summary['total_credit']:,}** ریال")
        lines.append(f"💸 برداشت: **{summary['total_debit']:,}** ریال")

        net = summary["net"]
        if net >= 0:
            lines.append(f"📈 خالص: **+{net:,}** ریال ✅")
        else:
            lines.append(f"📉 خالص: **{net:,}** ریال ⚠️")

        lines.append(f"\n📝 تعداد تراکنش: {summary['transaction_count']}")

        if summary["by_category"]:
            lines.append("\n📂 **دسته‌بندی هزینه‌ها:**")
            sorted_cats = sorted(summary["by_category"].items(),
                                 key=lambda x: x[1]["total"], reverse=True)
            for cat_name, cat_data in sorted_cats:
                lines.append(f"  {cat_data['icon']} {cat_name}: {cat_data['total']:,} ریال ({cat_data['count']} تراکنش)")

        return "\n".join(lines)


# ============================================================
# Storage
# ============================================================

DATA_DIR = os.path.expanduser("~/.hermes/sms_forwarder")
TRANSACTIONS_FILE = os.path.join(DATA_DIR, "transactions.json")


def save_transaction(transaction: dict):
    """Save a transaction to local JSON storage."""
    os.makedirs(DATA_DIR, exist_ok=True)

    existing = []
    if os.path.exists(TRANSACTIONS_FILE):
        with open(TRANSACTIONS_FILE, "r", encoding="utf-8") as f:
            existing = json.load(f)

    existing.append(transaction)

    with open(TRANSACTIONS_FILE, "w", encoding="utf-8") as f:
        json.dump(existing, f, ensure_ascii=False, indent=2)


def load_transactions() -> list:
    """Load all transactions."""
    if os.path.exists(TRANSACTIONS_FILE):
        with open(TRANSACTIONS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return []


def get_latest_balance() -> int:
    """Get the most recent balance from transactions."""
    transactions = load_transactions()
    if transactions:
        return transactions[-1].get("balance", 0)
    return 0


# ============================================================
# Main CLI
# ============================================================

if __name__ == "__main__":
    import sys

    parser = BankSmsParser()
    summary_gen = FinancialSummary()

    if len(sys.argv) > 1:
        # Parse single SMS
        sms_text = " ".join(sys.argv[1:])
        results = parser.parse_batch(sms_text)

        for r in results:
            save_transaction(r)
            print(json.dumps(r, ensure_ascii=False, indent=2))

        if results:
            print("\n" + "=" * 40)
            daily = summary_gen.daily_summary(results)
            print(summary_gen.format_summary_fa(daily))
    else:
        # Show all transactions summary
        transactions = load_transactions()
        if transactions:
            daily = summary_gen.daily_summary(transactions)
            print(summary_gen.format_summary_fa(daily))
            print(f"\n💰 موجودی فعلی: {get_latest_balance():,} ریال")
        else:
            print("📭 هنوز تراکنشی ثبت نشده.")
