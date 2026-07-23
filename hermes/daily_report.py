#!/usr/bin/env python3
"""
Daily Report Generator for Financial Tracker
Generates comprehensive daily, weekly, and monthly reports.
"""

import json
import sys
from datetime import datetime, timedelta
from pathlib import Path
from collections import defaultdict

DATA_DIR = Path.home() / ".hermes" / "sms_forwarder"
TRANSACTIONS_FILE = DATA_DIR / "transactions.json"

# Categories (same as main processor)
CATEGORIES = {
    "salary": {"name": "حقوق", "emoji": "💰"},
    "rent": {"name": "اجاره", "emoji": "🏠"},
    "utilities": {"name": "قبوض", "emoji": "💡"},
    "food": {"name": "خوراک", "emoji": "🍽️"},
    "transport": {"name": "حمل‌ونقل", "emoji": "🚗"},
    "shopping": {"name": "خرید آنلاین", "emoji": "🛒"},
    "atm": {"name": "خودپرداز", "emoji": "🏧"},
    "card_transfer": {"name": "کارت به کارت", "emoji": "💳"},
    "installment": {"name": "قسط", "emoji": "📅"},
    "deposit": {"name": "واریز", "emoji": "📥"},
    "withdrawal": {"name": "برداشت", "emoji": "📤"},
    "unknown": {"name": "نامشخص", "emoji": "❓"}
}

def load_transactions():
    if TRANSACTIONS_FILE.exists():
        with open(TRANSACTIONS_FILE, "r", encoding="utf-8") as f:
            return json.load(f)
    return []

def filter_by_date(transactions, start_date, end_date):
    """Filter transactions by date range"""
    result = []
    for t in transactions:
        try:
            txn_date = datetime.fromisoformat(t["timestamp"]).date()
            if start_date <= txn_date <= end_date:
                result.append(t)
        except:
            pass
    return result

def generate_report(transactions, title):
    """Generate formatted report"""
    if not transactions:
        return f"📊 *{title}*\n\nتراکنشی ثبت نشد."
    
    # Calculate totals
    total_credit = sum(t.get("amount", 0) or 0 for t in transactions if t.get("amount_type") == "credit")
    total_debit = sum(t.get("amount", 0) or 0 for t in transactions if t.get("amount_type") == "debit")
    
    # Group by category
    by_category = defaultdict(lambda: {"count": 0, "total": 0, "transactions": []})
    for t in transactions:
        cat = t.get("category", "unknown")
        by_category[cat]["count"] += 1
        by_category[cat]["total"] += t.get("amount", 0) or 0
        by_category[cat]["transactions"].append(t)
    
    # Build report
    lines = [f"📊 *{title}*\n"]
    lines.append(f"💰 *واریز:* {total_credit:,} ریال ({total_credit // 10:,} تومان)")
    lines.append(f"💸 *برداشت:* {total_debit:,} ریال ({total_debit // 10:,} تومان)")
    lines.append(f"📊 *خالص:* {total_credit - total_debit:,} ریال\n")
    
    lines.append("*دسته‌بندی:*")
    for cat_id in sorted(by_category.keys(), key=lambda x: -by_category[x]["total"]):
        data = by_category[cat_id]
        cat_info = CATEGORIES.get(cat_id, CATEGORIES["unknown"])
        lines.append(f"{cat_info['emoji']} {cat_info['name']}: {data['count']} تراکنش، {data['total']:,} ریال")
    
    # Unconfirmed
    unconfirmed = [t for t in transactions if not t.get("confirmed", False)]
    if unconfirmed:
        lines.append(f"\n⚠️ *{len(unconfirmed)} تراکنش تأیید نشده*")
    
    return "\n".join(lines)

def daily_report(date_str=None):
    """Generate daily report"""
    if date_str:
        target_date = datetime.strptime(date_str, "%Y-%m-%d").date()
    else:
        target_date = datetime.now().date()
    
    transactions = load_transactions()
    day_txns = filter_by_date(transactions, target_date, target_date)
    
    title = f"گزارش روزانه {target_date.strftime('%Y-%m-%d')}"
    return generate_report(day_txns, title)

def weekly_report():
    """Generate weekly report (last 7 days)"""
    transactions = load_transactions()
    end_date = datetime.now().date()
    start_date = end_date - timedelta(days=6)
    
    week_txns = filter_by_date(transactions, start_date, end_date)
    title = f"گزارش هفتگی {start_date} تا {end_date}"
    return generate_report(week_txns, title)

def monthly_report(year=None, month=None):
    """Generate monthly report"""
    if year is None or month is None:
        now = datetime.now()
        year = now.year
        month = now.month
    
    transactions = load_transactions()
    start_date = datetime(year, month, 1).date()
    if month == 12:
        end_date = datetime(year + 1, 1, 1).date() - timedelta(days=1)
    else:
        end_date = datetime(year, month + 1, 1).date() - timedelta(days=1)
    
    month_txns = filter_by_date(transactions, start_date, end_date)
    title = f"گزارش ماهانه {year}/{month:02d}"
    return generate_report(month_txns, title)

def category_report(category_id, days=30):
    """Generate report for a specific category"""
    transactions = load_transactions()
    end_date = datetime.now().date()
    start_date = end_date - timedelta(days=days)
    
    cat_txns = [t for t in filter_by_date(transactions, start_date, end_date) 
                if t.get("category") == category_id]
    
    cat_info = CATEGORIES.get(category_id, CATEGORIES["unknown"])
    title = f"گزارش {cat_info['name']} (۳۰ روز اخیر)"
    return generate_report(cat_txns, title)

def balance_history():
    """Show balance history"""
    transactions = load_transactions()
    balances = [(t["timestamp"][:10], t["balance"]) for t in transactions 
                if t.get("balance") and t["balance"] > 0]
    
    if not balances:
        return "📊 تاریخچه موجودی موجود نیست."
    
    # Get last balance per day
    daily_balances = {}
    for date, balance in balances:
        daily_balances[date] = balance
    
    lines = ["📊 *تاریخچه موجودی*\n"]
    for date in sorted(daily_balances.keys())[-7:]:  # Last 7 days
        lines.append(f"📅 {date}: {daily_balances[date]:,} ریال")
    
    return "\n".join(lines)

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: daily_report.py [daily|weekly|monthly|category|balance] [date/category_id]")
        sys.exit(1)
    
    cmd = sys.argv[1]
    
    if cmd == "daily":
        date = sys.argv[2] if len(sys.argv) > 2 else None
        print(daily_report(date))
    elif cmd == "weekly":
        print(weekly_report())
    elif cmd == "monthly":
        year = int(sys.argv[2]) if len(sys.argv) > 2 else None
        month = int(sys.argv[3]) if len(sys.argv) > 3 else None
        print(monthly_report(year, month))
    elif cmd == "category":
        cat_id = sys.argv[2] if len(sys.argv) > 2 else "unknown"
        print(category_report(cat_id))
    elif cmd == "balance":
        print(balance_history())
    else:
        print(f"Unknown command: {cmd}")
