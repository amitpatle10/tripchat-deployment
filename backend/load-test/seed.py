#!/usr/bin/env python3
"""
TripChat — Load Test Seed Script
=================================
Creates 2000 users and 25 groups across 5 categories in PostgreSQL,
then writes seed-output.json for k6 to consume.

User pools
----------
  filleruser0001–1000  →  exist only to fill "full" groups (not in k6 output)
  testuser0001–1000    →  used by k6 scenarios

Group distribution
------------------
  full   (3 groups, 1000 members each)  →  triggers GroupFullException in k6
  large  (5 groups,  500 members each)  →  high-capacity read/message tests
  normal (7 groups,   50 members each)  →  typical group operations
  small  (5 groups,   10 members each)  →  light group operations
  empty  (5 groups,    0 members)       →  join tests during k6 run

  testuser0001–0500   →  in all 5 large groups
  testuser0501–0850   →  split across 7 normal groups (50 per group, no overlap)
  testuser0851–0900   →  split across 5 small  groups (10 per group, no overlap)
  testuser0901–1000   →  free (no pre-assigned group — join empty groups in k6)

Usage
-----
  pip install -r requirements.txt

  python seed.py                        # idempotent: removes old seed data, re-seeds
  python seed.py --clean                # TRUNCATE all tables first (prompts confirmation)
  python seed.py --clean --force        # TRUNCATE without prompt (for CI)
  python seed.py --db-url postgresql://user:pass@host:5432/db
  python seed.py --output custom-output.json
"""

import argparse
import json
import os
import random
import sys
import uuid
from datetime import datetime, timezone

import bcrypt
import psycopg2
from psycopg2.extras import execute_values

# ── Constants ─────────────────────────────────────────────────────────────────

PASSWORD    = "TestPass1!"
INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # matches InviteCodeGenerator
                                                      # excludes O, 0, I, 1
BCRYPT_COST = 4   # cost 4 for seeding (~5ms/hash vs ~200ms at cost 10)
                  # BCrypt embeds the cost in the hash string — Spring's
                  # PasswordEncoder.matches() handles any cost factor correctly

# (category, count, member_count, display_name_prefix)
GROUP_SPEC = [
    ("full",   3, 1000, "Full Group"),
    ("large",  5,  500, "Large Group"),
    ("normal", 7,   50, "Normal Group"),
    ("small",  5,   10, "Small Group"),
    ("empty",  5,    0, "Empty Group"),
]

# ── Args ──────────────────────────────────────────────────────────────────────

def parse_args():
    parser = argparse.ArgumentParser(
        description="Seed TripChat load test data",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--db-url",
        default=os.getenv(
            "DATABASE_URL",
            "postgresql://tripchat:tripchat@localhost:5432/tripchat",
        ),
        help="PostgreSQL connection URL (default: env DATABASE_URL or localhost)",
    )
    parser.add_argument(
        "--clean",
        action="store_true",
        help="TRUNCATE all tables before seeding (destructive — prompts unless --force)",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Skip confirmation prompt when using --clean",
    )
    parser.add_argument(
        "--output",
        default="seed-output.json",
        help="Path to write the k6 data file (default: seed-output.json)",
    )
    return parser.parse_args()

# ── Helpers ───────────────────────────────────────────────────────────────────

def gen_invite_code(used: set) -> str:
    """Generate a unique 8-char invite code — same character set as InviteCodeGenerator.java."""
    while True:
        code = "".join(random.choices(INVITE_CHARS, k=8))
        if code not in used:
            used.add(code)
            return code


def remove_previous_seed_data(cur):
    """
    Delete only data created by this script — identified by email pattern.
    Safe to call on every run. Does NOT touch real user data.
    """
    print("Removing previous seed data (if any)...")

    # Must delete group_members before groups (FK constraint)
    cur.execute("""
        DELETE FROM group_members
        WHERE group_id IN (
            SELECT id FROM chat_groups
            WHERE name ~ '^(Full|Large|Normal|Small|Empty) Group [0-9]+'
        )
    """)
    cur.execute("""
        DELETE FROM chat_groups
        WHERE name ~ '^(Full|Large|Normal|Small|Empty) Group [0-9]+'
    """)
    cur.execute("""
        DELETE FROM users
        WHERE email ~ '^(testuser|filleruser)[0-9]+@tripchat\\.com$'
    """)


def insert_users(cur, rows):
    """Batch-insert users, skipping conflicts (idempotent).

    ON CONFLICT DO NOTHING (no column target) is required here because the
    unique constraint on email is a functional index — LOWER(email::text) —
    not a plain column unique constraint. PostgreSQL only allows a column
    target in ON CONFLICT when the constraint is a plain unique index.
    """
    execute_values(
        cur,
        """
        INSERT INTO users
          (id, email, username, display_name, password_hash,
           auth_provider, is_active, created_at, updated_at)
        VALUES %s
        ON CONFLICT DO NOTHING
        """,
        rows,
    )


def insert_memberships(cur, rows):
    """Batch-insert group memberships."""
    execute_values(
        cur,
        """
        INSERT INTO group_members (id, group_id, user_id, role, joined_at)
        VALUES %s
        """,
        rows,
    )

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()

    print(f"Connecting to database...")
    conn = psycopg2.connect(args.db_url)
    conn.autocommit = False
    cur = conn.cursor()

    try:
        # ── Clean / reset ─────────────────────────────────────────────────────

        if args.clean:
            if not args.force:
                answer = input(
                    "\nWARNING: --clean will TRUNCATE ALL tables (users, groups, memberships).\n"
                    "This cannot be undone. Type 'yes' to continue: "
                )
                if answer.strip().lower() != "yes":
                    print("Aborted.")
                    sys.exit(0)
            print("Truncating all tables...")
            cur.execute("TRUNCATE group_members, chat_groups, users CASCADE")
            print("Done.\n")
        else:
            remove_previous_seed_data(cur)

        now = datetime.now(timezone.utc)

        # ── Hash password once, reuse for all 2000 users ──────────────────────
        #
        # All seed users share the same password (TestPass1!) and therefore
        # the same hash. BCrypt uses a random salt per call, so we compute
        # one hash and store it for everyone. Spring's matches() verifies it
        # correctly regardless of which user calls it.
        #
        print(f"Hashing password (BCrypt cost {BCRYPT_COST})...")
        pw_hash = bcrypt.hashpw(PASSWORD.encode(), bcrypt.gensalt(BCRYPT_COST)).decode()
        print(f"  {pw_hash[:29]}...\n")

        # ── Create filler users (filleruser0001–1000) ─────────────────────────
        #
        # Filler users exist solely to fill the "full" groups to 1000 members.
        # They are NOT included in seed-output.json — k6 never uses them.
        # This keeps the k6 test user pool clean and outside the full groups,
        # so k6 users can always test the GroupFullException path.

        print("Creating 1000 filler users...")
        filler_rows = [
            (
                str(uuid.uuid4()),
                f"filleruser{i:04d}@tripchat.com",
                f"filleruser{i:04d}",
                f"Filler {i:04d}",
                pw_hash, "LOCAL", True, now, now,
            )
            for i in range(1, 1001)
        ]
        insert_users(cur, filler_rows)

        cur.execute(
            "SELECT id FROM users WHERE email ~ '^filleruser[0-9]+@tripchat\\.com$' ORDER BY email"
        )
        filler_ids = [row[0] for row in cur.fetchall()]
        print(f"  {len(filler_ids)} filler users ready.")

        # ── Create k6 test users (testuser0001–1000) ──────────────────────────

        print("Creating 1000 k6 test users...")
        test_rows = [
            (
                str(uuid.uuid4()),
                f"testuser{i:04d}@tripchat.com",
                f"testuser{i:04d}",
                f"Test User {i:04d}",
                pw_hash, "LOCAL", True, now, now,
            )
            for i in range(1, 1001)
        ]
        insert_users(cur, test_rows)

        cur.execute(
            "SELECT id FROM users WHERE email ~ '^testuser[0-9]+@tripchat\\.com$' ORDER BY email"
        )
        test_ids = [row[0] for row in cur.fetchall()]
        print(f"  {len(test_ids)} test users ready.\n")

        # ── Member pools ──────────────────────────────────────────────────────
        #
        # Pool assignment:
        #   full   → all 1000 filler users (every full group has same 1000 members)
        #   large  → testuser0001-0500 (all in every large group — 500 members each)
        #   normal → testuser0501-0850 (50 per group, split evenly, no overlap)
        #   small  → testuser0851-0900 (10 per group, split evenly, no overlap)
        #   empty  → no pre-seeded members
        #
        #   testuser0901-1000 (100 users) are free — not in any group.
        #   k6 uses these for the "join empty group" scenario.

        pools = {
            "full":   filler_ids,        # 1000 → 1000 per group
            "large":  test_ids[:500],    # 500  → 500 per group (all in every large group)
            "normal": test_ids[500:850], # 350  → 50 per group × 7 = 350 (no overlap)
            "small":  test_ids[850:900], # 50   → 10 per group × 5 = 50  (no overlap)
            "empty":  [],
        }
        admins = {
            "full":   filler_ids[0],   # filleruser0001 is ADMIN of all full groups
            "large":  test_ids[0],     # testuser0001   is ADMIN of all k6 groups
            "normal": test_ids[0],
            "small":  test_ids[0],
            "empty":  test_ids[0],
        }

        # ── Create groups + memberships ───────────────────────────────────────

        used_codes   = set()
        output_groups = {cat: [] for cat, *_ in GROUP_SPEC}

        print("Creating groups and memberships...\n")

        for category, count, member_count, name_prefix in GROUP_SPEC:
            pool     = pools[category]
            admin_id = admins[category]

            for i in range(1, count + 1):
                group_id    = str(uuid.uuid4())
                invite_code = gen_invite_code(used_codes)
                name        = f"{name_prefix} {i:02d}"
                description = (
                    f"Load test — {category} group "
                    f"({member_count} members pre-seeded)"
                    if member_count > 0
                    else "Load test — empty group (join during k6 run)"
                )

                # Insert group
                cur.execute(
                    """
                    INSERT INTO chat_groups
                      (id, name, description, created_by, invite_code,
                       is_active, created_at, updated_at)
                    VALUES (%s, %s, %s, %s, %s, true, %s, %s)
                    """,
                    (group_id, name, description, admin_id, invite_code, now, now),
                )

                # Resolve which users go into THIS group
                if category in ("full", "large"):
                    # Every group in this category gets the entire pool
                    members = pool
                elif member_count > 0:
                    # Split pool evenly across groups — zero overlap
                    start   = (i - 1) * member_count
                    members = pool[start : start + member_count]
                else:
                    members = []

                # Build membership rows: admin first, then pool members
                membership_rows = [(str(uuid.uuid4()), group_id, admin_id, "ADMIN", now)]
                for uid in members:
                    if uid == admin_id:
                        continue  # already inserted as ADMIN
                    membership_rows.append(
                        (str(uuid.uuid4()), group_id, uid, "MEMBER", now)
                    )

                if membership_rows:
                    insert_memberships(cur, membership_rows)

                actual_count = len(membership_rows)
                output_groups[category].append({
                    "id":          group_id,
                    "name":        name,
                    "inviteCode":  invite_code,
                    "memberCount": actual_count,
                })

                # Commit per group — avoids one giant transaction for 6000 memberships
                conn.commit()

                print(
                    f"  [{category:6s}]  {name:<20s}  "
                    f"{actual_count:4d} members  |  code: {invite_code}"
                )

        # ── Write seed-output.json ────────────────────────────────────────────
        #
        # k6 reads this at startup. Each VU picks a user from testUsers,
        # logs in to get a JWT, then runs scenarios using the group pools.
        #
        # freeUsers (testuser0901-1000) are not in any group — use them in
        # the "join empty group" scenario so they can join cleanly.

        output = {
            "password":  PASSWORD,
            "testUsers": [
                {
                    "email":    f"testuser{i:04d}@tripchat.com",
                    "username": f"testuser{i:04d}",
                }
                for i in range(1, 1001)
            ],
            "freeUsers": [
                {
                    "email":    f"testuser{i:04d}@tripchat.com",
                    "username": f"testuser{i:04d}",
                }
                for i in range(901, 1001)
            ],
            "groups": output_groups,
        }

        output_path = os.path.join(os.path.dirname(__file__), args.output)
        with open(output_path, "w") as f:
            json.dump(output, f, indent=2)

        print(f"\nOutput written → {output_path}")
        print_summary(output_groups)

    except Exception:
        conn.rollback()
        import traceback
        traceback.print_exc()
        sys.exit(1)
    finally:
        cur.close()
        conn.close()

# ── Summary ───────────────────────────────────────────────────────────────────

def print_summary(groups: dict):
    total_groups      = sum(len(v) for v in groups.values())
    total_memberships = sum(g["memberCount"] for grps in groups.values() for g in grps)

    print()
    print("── Seed Summary ──────────────────────────────────────────────────")
    print(f"  {'Category':<8}  {'Groups':>6}  {'Members/group':>14}  {'Total memberships':>18}")
    print(f"  {'─'*8}  {'─'*6}  {'─'*14}  {'─'*18}")
    for category, items in groups.items():
        if items:
            per_group = items[0]["memberCount"]
            total     = sum(g["memberCount"] for g in items)
            print(f"  {category:<8}  {len(items):>6}  {per_group:>14}  {total:>18,}")

    print()
    print(f"  Total groups:          {total_groups}")
    print(f"  Total memberships:     {total_memberships:,}")
    print(f"  Filler users in DB:    1,000  (filleruser0001-1000, not in k6 output)")
    print(f"  k6 test users in DB:   1,000  (testuser0001-1000)")
    print(f"    ├─ in large groups:    500  (testuser0001-0500)")
    print(f"    ├─ in normal groups:   350  (testuser0501-0850)")
    print(f"    ├─ in small groups:     50  (testuser0851-0900)")
    print(f"    └─ free (no group):    100  (testuser0901-1000 → for join tests)")
    print("──────────────────────────────────────────────────────────────────")


if __name__ == "__main__":
    main()
