#!/usr/bin/env python3
"""
Financial Transaction Processor for SmsForwarder
Reads bank SMS from Telegram bot, categorizes transactions,
and manages confirmation workflow.
"""

import json
import os
import re
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

# Telegram Bot API
import urllib.request
import urllib.parse
import urllib.error

# ─── Configuration ───────────────────────────────────────────────
BOT_TOKEN = "8883984362:AAF1lEfQmJn06jHxdC6Zf_aHlcDT72TGUgQ"
CHAT_ID = "587763062"
DATA_DIR = Path.home() / ".hermes" / "sms_forwarder"
TRANSACTIONS_FILE = DATA_DIR / "transactions.json"
PENDING_FILE = DATA_DIR / "pending_confirmations.json"
OFFSET_FILE = DATA_DIR / "telegram_offset.txt"
CATEGORIES_FILE = DATA_DIR / "categories.json"

# ─── Categories ──────────────────────────────────────────────────
DEFAULT_CATEGORIES = {
    "salary": {"name": "حقوق", "emoji": "💰", "keywords": ["حقوق", "salary", "workers"]},
    "rent": {"name": "اجاره", "emoji": "🏠", "keywords": ["اجاره", "رهن", "rent"]},
    "utilities": {"name": "قبوض", "emoji": "💡", "keywords": ["آب", "برق", "گاز", "شارژ", "phone", "internet"]},
    "food": {"name": "خوراک", "emoji": "🍽️", "keywords": ["رستوران", "فست‌فود", "سوپرمارکت", "food", "restaurant", "cafe", "coffee"]},
    "transport": {"name": "حمل‌ونقل", "emoji": "🚗", "keywords": ["بنزین", "اسنپ", "تپسی", "gas", "uber", "taxi"]},
    "shopping": {"name": "خرید آنلاین", "emoji": "🛒", "keywords": ["دیجی‌کالا", "فروشگاه", "digikala", "amazon"]},
    "atm": {"name": "خودپرداز", "emoji": "🏧", "keywords": ["خودپرداز", "atm"]},
    "card_transfer": {"name": "کارت به کارت", "emoji": "💳", "keywords": ["کارت به کارت", "transfer"]},
    "installment": {"name": "قسط", "emoji": "📅", "keywords": ["قسط", "وام", "installment", "loan"]},
    "deposit": {"name": "واریز", "emoji": "📥", "keywords": []},
    "withdrawal": {"name": "برداشت", "emoji": "📤", "keywords": []},
    "unknown": {"name": "نامشخص", "emoji": "❓", "keywords": []}
}

# ─── Telegram API ────────────────────────────────────────────────
def telegram_api(method: str, params: dict = None) -> dict:
    """Call Telegram Bot API"""
    url = f"https://api.telegram.org/bot{BOT_TOKEN}/{method}"
    if params:
        data = urllib.parse.urlencode(params).encode("utf-8")
        req = urllib.request.Request(url, data=data)
    else:
        req = urllib.request.Request(url)
    
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        print(f"Telegram API error: {e}", file=sys.stderr)
        return {"ok": False, "error": str(e)}

def send_message(text: str, reply_to: int = None, parse_mode: str = "Markdown"):
    """Send message to chat"""
    params = {
        "chat_id": CHAT_ID,
        "text": text,
        "parse_mode": parse_mode
    }
    if reply_to:
        params["reply_to_message_id"] = reply_to
    return telegram_api("sendMessage", params)

def get_updates(offset: int = 0) -> list:
    """Get new messages from Telegram"""
    params = {"offset": offset, "limit": 100, "timeout": 5}
    result = telegram_api("getUpdates", params)
    if result.get("ok"):
        return result.get("result", [])
    return []

# ─── SMS Parser ──────────────────────────────────────────────────
def parse_bank_sms(body: str) -> dict:
    """Parse bank SMS format"""
    result = {
        "card_number": None,
        "amount": None,
        "amount_type": None,  # "credit" or "debit"
        "date": None,
        "balance": None,
        "raw": body
    }
    
    # Card number (14-19 digits)
    card_match = re.search(r'\b(\d{14,19})\b', body)
    if card_match:
        result["card_number"] = card_match.group(1)
    
    # Amount with +/- sign
    amount_match = re.search(r'([\d,]+)\s*([+-])', body)
    if amount_match:
        amount_str = amount_match.group(1).replace(",", "")
        result["amount"] = int(amount_str)
        result["amount_type"] = "credit" if amount_match.group(2) == "+" else "debit"
    else:
        # Try without sign (assume debit)
        amount_match2 = re.search(r'([\d,]+)', body)
        if amount_match2 and not re.search(r'\d{14,19}', amount_match2.group(1)):
            amount_str = amount_match2.group(1).replace(",", "")
            if len(amount_str) > 3:  # Likely an amount, not a date
                result["amount"] = int(amount_str)
                result["amount_type"] = "debit"
    
    # Jalali date
    date_match = re.search(r'(\d{4}/\d{1,2}/\d{1,2}(?:-\d{1,2}:\d{2})?)', body)
    if date_match:
        result["date"] = date_match.group(1)
    
    # Balance
    balance_match = re.search(r'مانده[:\s]*([\d,]+)', body)
    if balance_match:
        result["balance"] = int(balance_match.group(1).replace(",", ""))
    
    return result

def categorize_transaction(text: str, categories: dict = None) -> str:
    """Auto-categorize based on keywords"""
    if categories is None:
        categories = DEFAULT_CATEGORIES
    
    text_lower = text.lower()
    
    for cat_id, cat_info in categories.items():
        for keyword in cat_info.get("keywords", []):
            if keyword.lower() in text_lower:
                return cat_id
    
    return "unknown"

# ─── Database ────────────────────────────────────────────────────
def load_transactions() -> list:
    """Load transactions from file"""
    if TRANSACTIONS_FILE.exists():
        with open(TRANSACTIONS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return []

def save_transactions(transactions: list):
    """Save transactions to file"""
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with open(TRANSACTIONS_FILE, "w", encoding="utf-8") as f:
        json.dump(transactions, f, ensure_ascii=False, indent=2)

def load_pending() -> list:
    """Load pending confirmations"""
    if PENDING_FILE.exists():
        with open(PENDING_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return []

def save_pending(pending: list):
    """Save pending confirmations"""
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with open(PENDING_FILE, "w", encoding="utf-8") as f:
        json.dump(pending, f, ensure_ascii=False, indent=2)

def load_categories() -> dict:
    """Load custom categories"""
    if CATEGORIES_FILE.exists():
        with open(CATEGORIES_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return DEFAULT_CATEGORIES

def get_offset() -> int:
    """Get last Telegram update offset"""
    if OFFSET_FILE.exists():
        with open(OFFSET_FILE, "r") as f:
            return int(f.read().strip())
    return 0

def save_offset(offset: int):
    """Save Telegram update offset"""
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    with open(OFFSET_FILE, "w") as f:
        f.write(str(offset))

# ─── Processing ──────────────────────────────────────────────────
def process_new_messages():
    """Read and process new Telegram messages"""
    offset = get_offset()
    updates = get_updates(offset)
    
    if not updates:
        return
    
    transactions = load_transactions()
    pending = load_pending()
    categories = load_categories()
    
    new_count = 0
    
    for update in updates:
        update_id = update["update_id"]
        
        # Update offset
        if update_id >= offset:
            save_offset(update_id + 1)
        
        # Only process messages
        if "message" not in update:
            continue
        
        message = update["message"]
        text = message.get("text", "")
        msg_id = message.get("message_id")
        
        # Skip bot commands
        if text.startswith("/"):
            continue
        
        # Check if this looks like a bank SMS
        if not is_bank_sms(text):
            continue
        
        # Parse the SMS
        parsed = parse_bank_sms(text)
        category = categorize_transaction(text, categories)
        
        # Create transaction record
        transaction = {
            "id": f"txn_{int(time.time())}_{msg_id}",
            "timestamp": datetime.now().isoformat(),
            "telegram_msg_id": msg_id,
            "card_number": parsed["card_number"],
            "amount": parsed["amount"],
            "amount_type": parsed["amount_type"],
            "balance": parsed["balance"],
            "bank_date": parsed["date"],
            "category": category,
            "confirmed": False,
            "raw_text": text,
            "description": None
        }
        
        transactions.append(transaction)
        new_count += 1
        
        # Send confirmation request
        send_confirmation_request(transaction, msg_id)
    
    save_transactions(transactions)

def is_bank_sms(text: str) -> bool:
    """Check if message looks like a bank SMS"""
    indicators = [
        r'\b\d{14,19}\b',  # Card number
        r'مانده',  # Balance
        r'[+-]\s*$',  # Amount with sign
        r'\d{4}/\d{1,2}/\d{1,2}',  # Jalali date
    ]
    return any(re.search(p, text) for p in indicators)

def send_confirmation_request(transaction: dict, reply_to: int):
    """Send confirmation request to user"""
    cat_info = DEFAULT_CATEGORIES.get(transaction["category"], DEFAULT_CATEGORIES["unknown"])
    
    amount_str = f"{transaction['amount']:,}" if transaction["amount"] else "؟"
    direction = "واریز" if transaction["amount_type"] == "credit" else "برداشت"
    
    text = f"""🏦 *تراکنش جدید*

{cat_info['emoji']} *دسته:* {cat_info['name']}
💰 *مبلغ:* {amount_str} ریال ({direction})
💳 *کارت:* {transaction['card_number'] or 'نامشخص'}
📅 *تاریخ:* {transaction['bank_date'] or 'نامشخص'}
📊 *مانده:* {transaction['balance'] or '؟'}

_آیا این تراکنش درسته؟_
✅ تأیید / ❌ رد / ✏️ توضیح بده"""
    
    send_message(text, reply_to=reply_to)

# ─── Confirmation Handler ────────────────────────────────────────
def handle_confirmation(text: str, msg_id: int):
    """Handle user's confirmation response"""
    pending = load_pending()
    transactions = load_transactions()
    
    # Find the last unconfirmed transaction
    unconfirmed = [t for t in transactions if not t["confirmed"]]
    if not unconfirmed:
        return
    
    last_txn = unconfirmed[-1]
    
    if text in ["✅", "تأیید", "تایید", "yes", "ok"]:
        last_txn["confirmed"] = True
        send_message("✅ تأیید شد!", reply_to=msg_id)
    elif text in ["❌", "رد", "no", "cancel"]:
        last_txn["category"] = "rejected"
        last_txn["confirmed"] = True
        send_message("❌ رد شد. از لیست حذف میشه.", reply_to=msg_id)
    else:
        # User provided description
        last_txn["description"] = text
        last_txn["confirmed"] = True
        send_message(f"✅ ثبت شد: {text}", reply_to=msg_id)
    
    save_transactions(transactions)

# ─── Daily Report ────────────────────────────────────────────────
def generate_daily_report(date_str: str = None):
    """Generate daily financial report"""
    if date_str is None:
        date_str = datetime.now().strftime("%Y-%m-%d")
    
    transactions = load_transactions()
    
    # Filter today's transactions
    today_txns = [
        t for t in transactions
        if t["timestamp"].startswith(date_str) and t["category"] != "rejected"
    ]
    
    if not today_txns:
        send_message("📊 *گزارش روزانه*\n\nامروز تراکنشی ثبت نشد.")
        return
    
    # Calculate totals
    total_credit = sum(t["amount"] for t in today_txns if t["amount_type"] == "credit")
    total_debit = sum(t["amount"] for t in today_txns if t["amount_type"] == "debit")
    
    # Group by category
    by_category = {}
    for t in today_txns:
        cat = t["category"]
        if cat not in by_category:
            by_category[cat] = {"count": 0, "total": 0}
        by_category[cat]["count"] += 1
        by_category[cat]["total"] += t["amount"] or 0
    
    # Build report
    lines = [f"📊 *گزارش مالی روز {date_str}*\n"]
    
    lines.append(f"💰 *واریز:* {total_credit:,} ریال")
    lines.append(f"💸 *برداشت:* {total_debit:,} ریال")
    lines.append(f"📊 *خالص:* {total_credit - total_debit:,} ریال\n")
    
    lines.append("*دسته‌بندی:*")
    for cat_id, data in sorted(by_category.items(), key=lambda x: -x[1]["total"]):
        cat_info = DEFAULT_CATEGORIES.get(cat_id, DEFAULT_CATEGORIES["unknown"])
        lines.append(f"{cat_info['emoji']} {cat_info['name']}: {data['count']} تراکنش، {data['total']:,} ریال")
    
    # Unconfirmed count
    unconfirmed = [t for t in today_txns if not t["confirmed"]]
    if unconfirmed:
        lines.append(f"\n⚠️ *{len(unconfirmed)} تراکنش تأیید نشده*")
    
    send_message("\n".join(lines))

# ─── Main ────────────────────────────────────────────────────────
if __name__ == "__main__":
    if len(sys.argv) > 1:
        cmd = sys.argv[1]
        if cmd == "report":
            date = sys.argv[2] if len(sys.argv) > 2 else None
            generate_daily_report(date)
        elif cmd == "process":
            process_new_messages()
        elif cmd == "status":
            txns = load_transactions()
            pending = [t for t in txns if not t["confirmed"]]
            print(f"Total transactions: {len(txns)}")
            print(f"Pending confirmation: {len(pending)}")
        else:
            print("Usage: financial_processor.py [process|report|status]")
    else:
        # Default: process new messages
        process_new_messages()
