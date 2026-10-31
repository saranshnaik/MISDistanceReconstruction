#!/usr/bin/env bash
# Walks through the main flows against a running instance and prints what happened.
#
#   docker compose up --build        (in another terminal)
#   requests/demo.sh                 (or: requests/demo.sh http://localhost:18080)
#
# Every run uses fresh trip refs and fresh roads, so it can be repeated against the same server.
# Needs: bash, curl, sed, grep. Demo accounts must exist (SEED_DEMO_USERS is on by default).

BASE=${1:-http://localhost:8080}
cd "$(dirname "$0")" || exit 1
RUN=$(date +%s)
REF1="DEMO-1-$RUN"
REF2="DEMO-2-$RUN"
TMP=$(mktemp -d)

say()   { printf '\n\033[1m== %s\033[0m\n' "$*"; }
# pull one field out of a JSON document (the trip summary keeps every key unique)
field() { sed -E 's/.*"'"$1"'":("[^"]*"|[^,}]*).*/\1/' | tr -d '"'; }
login() {
  curl -s -X POST "$BASE/api/auth/token" -H 'Content-Type: application/json' \
       -d "{\"username\":\"$1\",\"password\":\"demo-pass\"}" | field accessToken
}
code()  { curl -s -o /dev/null -w '%{http_code}' "$@"; }
get()   { curl -s "$BASE$2" -H "Authorization: Bearer $1"; }
post()  { # token path [json-file]
  if [ -n "$3" ]; then
    curl -s -X POST "$BASE$2" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' --data @"$3"
  else
    curl -s -X POST "$BASE$2" -H "Authorization: Bearer $1"
  fi
}
summary() { # trip JSON on stdin
  local json; json=$(cat)
  printf '   state=%s  billing=%s  rebuilt=%s km  naive=%s km  gaps=%s  provider calls=%s  rules=%s\n' \
    "$(echo "$json" | field distanceState)" "$(echo "$json" | field billingState)" \
    "$(echo "$json" | field billableKm)" "$(echo "$json" | field naiveKm)" \
    "$(echo "$json" | field routeRequests)" "$(echo "$json" | field providerCalls)" \
    "$(echo "$json" | field ruleVersion)"
}

# trip files with fresh refs; the outage trip also gets a fresh stretch of road nobody has cached
sed "s/\"DEMO-1\"/\"$REF1\"/" sample-trip.json > "$TMP/trip1.json"
# ($|[^0-9]) because the longitude is the last key of some JSON objects, so it ends its line
NEW_LON=$((RANDOM + 1000))
sed -E "s/\"DEMO-2\"/\"$REF2\"/; s/77\.9(\$|[^0-9])/77.$NEW_LON\1/g" sample-trip-outage.json > "$TMP/trip2.json"

say "waiting for the server (the first seconds report OUT_OF_SERVICE while demo accounts are created)"
for i in $(seq 1 60); do
  curl -s "$BASE/actuator/health" | grep -q '"status":"UP"' && break
  sleep 2
done
curl -s "$BASE/actuator/health"; echo

say "log in as the demo accounts"
OP=$(login acme-operator); ADMIN=$(login acme-admin); EMP=$(login acme-employee); OTHER=$(login globex-operator)
[ -n "$OP" ] && [ -n "$ADMIN" ] && echo "   tokens received (operator, admin, employee, and an operator of ANOTHER tenant)" \
  || { echo "   login failed: is the server up and are demo users on?"; exit 1; }

say "1. submit a trip with a 22-minute GPS hole and a drop-off inside it"
post "$OP" /api/trips "$TMP/trip1.json" | summary
echo "   (naive = just adding up the pings; rebuilt = anchors + routed gaps)"

say "2. submit the same trip again: nothing changes"
FIRST=$(get "$OP" "/api/trips/$REF1" | field computationId)
SECOND=$(post "$OP" /api/trips "$TMP/trip1.json" | field computationId)
echo "   computation id first=$FIRST, after resubmit=$SECOND"

say "3. why is every kilometre there? (segments by the rule that priced them)"
get "$OP" "/api/trips/$REF1/segments" | grep -o '"rule":"[A-Z_]*"' | sort | uniq -c | sed 's/^/   /'

say "4. who may do what"
echo "   employee reading segments:        HTTP $(code "$BASE/api/trips/$REF1/segments" -H "Authorization: Bearer $EMP")   (403 expected)"
echo "   operator closing an invoice:      HTTP $(code -X POST "$BASE/api/trips/$REF1/bill" -H "Authorization: Bearer $OP")   (403 expected)"
echo "   another tenant reading the trip:  HTTP $(code "$BASE/api/trips/$REF1" -H "Authorization: Bearer $OTHER")   (404 expected)"
echo "   no token at all:                  HTTP $(code "$BASE/api/trips/$REF1")   (401 expected)"

say "5. admin closes the invoice"
post "$ADMIN" "/api/trips/$REF1/bill" | summary
echo "   billedKm=$(get "$ADMIN" "/api/trips/$REF1" | field billedKm) (frozen from now on)"

say "6. late pings fill the hole after billing: a signed adjustment, the invoice is not edited"
post "$OP" "/api/trips/$REF1/pings" late-pings.json | summary
AFTER=$(get "$ADMIN" "/api/trips/$REF1")
echo "   billedKm=$(echo "$AFTER" | field billedKm)  finalBilledKm=$(echo "$AFTER" | field finalBilledKm)"
echo "   adjustments: $(get "$ADMIN" "/api/trips/$REF1/adjustments" | grep -o '"deltaKm":[-0-9.]*' | tr '\n' ' ')"

say "7. FAILURE: the routing provider goes down"
curl -s -X POST "$BASE/api/admin/routing/outage" -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"down":true}'; echo
echo "   submitting a trip that needs routing:"
post "$OP" /api/trips "$TMP/trip2.json" | summary
echo "   trying to bill the estimate:       HTTP $(code -X POST "$BASE/api/trips/$REF2/bill" -H "Authorization: Bearer $ADMIN")   (409 expected)"

say "8. RECOVERY: provider back, trip recomputed, now it can be billed"
curl -s -X POST "$BASE/api/admin/routing/outage" -H "Authorization: Bearer $ADMIN" \
     -H 'Content-Type: application/json' -d '{"down":false}'; echo
sleep 1
post "$OP" "/api/trips/$REF2/recompute" | summary
echo "   billing it now:                    HTTP $(code -X POST "$BASE/api/trips/$REF2/bill" -H "Authorization: Bearer $ADMIN")   (200 expected)"

say "9. what an on-call engineer would look at"
get "$ADMIN" /actuator/prometheus | grep -E '^(distance_trips_degraded|distance_routing_calls_total|distance_routing_cache_hits_total|distance_routing_circuit_state|distance_billing_adjustments_total|distance_pings_dropped_total)' | sed 's/^/   /'

rm -rf "$TMP"
printf '\nDone.\n'
