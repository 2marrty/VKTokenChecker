import requests
import pyperclip
import json
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

API_VERSION = "5.199"

def vk_api(method, params, token):
    params["access_token"] = token
    params["v"] = API_VERSION
    try:
        response = requests.post(
            f"https://api.vk.com/method/{method}",
            data=params,
            timeout=10
        )
        return response.json()
    except requests.exceptions.RequestException as e:
        return {"error": {"error_msg": str(e)}}

def check_token(token):
    """Проверяет токен через users.get"""
    token = token.strip()
    if not token:
        return None, "пустой"

    result = vk_api("users.get", {}, token)

    if "error" in result:
        code = result["error"].get("error_code", 0)
        msg = result["error"].get("error_msg", "")
        if code in (5, 1117):
            return token, "❌ невалидный"
        elif code == 6:
            return token, "⏳ rate limit"
        else:
            return token, f"⚠️ ошибка {code}: {msg}"

    user = result.get("response", [{}])[0]
    name = f"{user.get('first_name', '')} {user.get('last_name', '')}".strip()
    uid = user.get("id", "?")
    return token, f"✅ валидный | id{uid} {name}"

def load_tokens(filepath):
    """Загружает токены из файла (по одному на строку или JSON-массив)"""
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read().strip()

    # Попытка распарсить как JSON
    try:
        data = json.loads(content)
        if isinstance(data, list):
            return [t.strip() for t in data if t.strip()]
    except json.JSONDecodeError:
        pass

    # Иначе — построчно
    return [line.strip() for line in content.splitlines() if line.strip()]

def check_all_tokens(tokens, max_workers=5, delay=0.34):
    """
    Проверяет все токены.
    delay=0.34 — ~3 запроса/сек, чтобы не словить rate limit.
    """
    valid = []
    invalid = []
    errors = []

    total = len(tokens)
    print(f"\n🔍 Начинаю проверку {total} токенов...\n")

    results = []
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(check_token, t): t for t in tokens}
        for i, future in enumerate(as_completed(futures), 1):
            token, status = future.result()
            short = (token[:20] + "...") if token and len(token) > 20 else token
            print(f"[{i}/{total}] {short} — {status}")
            results.append((token, status))
            time.sleep(delay / max_workers)

    # Сортируем по результату
    for token, status in results:
        if "✅" in status:
            valid.append(token)
        elif "❌" in status:
            invalid.append(token)
        else:
            errors.append(token)

    return valid, invalid, errors

def save_results(valid, invalid, errors):
    with open("valid_tokens.txt", "w") as f:
        f.write("\n".join(valid))
    with open("invalid_tokens.txt", "w") as f:
        f.write("\n".join(invalid))
    if errors:
        with open("error_tokens.txt", "w") as f:
            f.write("\n".join(errors))

def create_group_call(token):
    result = vk_api("calls.start", {}, token)
    if "error" in result:
        print(f"Ошибка создания звонка: {result['error']['error_msg']}")
        return None

    call_data = result.get("response", {})
    join_link = call_data.get("join_link") or call_data.get("url")

    if join_link:
        print(f"\n✅ Звонок создан!")
        print(f"🔗 Ссылка: {join_link}")
        try:
            pyperclip.copy(join_link)
            print("📋 Ссылка скопирована в буфер обмена!")
        except:
            pass
        return join_link
    else:
        print("Не удалось получить ссылку:", call_data)
        return None

# ───────────────────────────────────────────
if __name__ == "__main__":
    import sys

    # Если передан файл — проверяем токены из него
    if len(sys.argv) > 1:
        filepath = sys.argv[1]
        tokens = load_tokens(filepath)
        valid, invalid, errors = check_all_tokens(tokens)

        print(f"\n{'='*40}")
        print(f"✅ Валидных:    {len(valid)}")
        print(f"❌ Невалидных:  {len(invalid)}")
        print(f"⚠️  С ошибками: {len(errors)}")
        print(f"{'='*40}")

        save_results(valid, invalid, errors)
        print("\n💾 Результаты сохранены в valid_tokens.txt / invalid_tokens.txt")

        # Создаём звонок первым валидным токеном
        if valid:
            print(f"\n📞 Создаю звонок с первым валидным токеном...")
            create_group_call(valid[0])

    # Иначе — используем один токен вручную
    else:
        TOKEN = "vk1.a.rlrlwEJ2JQcX0XCaEUMwqCsoONjXyrc43v2XzLg7rw7xy_rwRzuWOKTzRIdQgJw5MjjyqEcpEWzWQHiatEKVUp9Je0hcUNGhmJA7_EckBatFhSu7I80MEq-wxRKUAMmq9MkrUTdnNN6vL1hxA8eiKTTtUDl9UTVXUeuwYftS1Uuj_11UShTeJFdtdQdhiRQV5zW41jYdtHvW9ACfrSMtvg"
        create_group_call(TOKEN)