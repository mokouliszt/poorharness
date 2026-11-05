# PoorHarness's command runner in a cloud sandbox: an Upstash box or a Modal
# sandbox (docs/adr/box-first.md, docs/adr/modal-first.md).
#
#   run   <cwd> <timeout> <command>                  runs it in this call
#   start <dir> <cwd> <timeout> <command> <wait>     runs it apart, then waits as "wait" does
#   wait  <dir> <wait>                               waits up to <wait> s for a started command
#   alive <limit> [folder...]                        the app is here: makes the folders, starts the watchdog
#
# Box's gateway ends a call after 300 s, so there a command allowed longer is
# started apart and waited for over several calls (Modal has no such limit). A started command still running after
# <wait> s exits 75 with "poordsh-runner: pending" on stderr; the app calls "wait"
# again. Output goes to files, never to this call's pipes, so "cmd &" lets the
# call return while the background process keeps running. stdout and stderr are
# each capped at 1 MiB. A command past its timeout is killed with its process
# group and exits 124.
#
# Every call marks the app as present and records its session. The watchdog
# ("alive" starts one if none runs) ends every recorded session, with what was
# left running in the background, once the app has not been here for <limit> s:
# a phone that died or went offline cannot leave a loop burning the month's CPU.
# The app also pauses an idle box itself; pausing ends every process anyway.
set -u
CAP=1048576
S=${POORDSH_STATE:-/tmp/.poordsh}
mkdir -p "$S"
touch "$S/alive"
sid=$(ps -o sid= -p $$ | tr -d ' ')
grep -qx "$sid" "$S/sessions" 2> /dev/null || echo "$sid" >> "$S/sessions"

WATCHDOG='
limit=$1
S=$2
every=30
[ "$limit" -lt "$every" ] && every=$limit
while sleep "$every"; do
  last=$(stat -c %Y "$S/alive" 2> /dev/null || echo 0)
  [ $(($(date +%s) - last)) -lt "$limit" ] && continue
  while read -r s; do
    [ -n "$s" ] && [ "$s" != 1 ] && [ "$s" != $$ ] || continue
    sudo -n pkill -KILL -s "$s" 2> /dev/null || pkill -KILL -s "$s" 2> /dev/null
  done < "$S/sessions"
  : > "$S/sessions"
  rm -f "$S/watchdog"
  exit 0
done'

finish() { # <dir> <status>: print the outputs, clean up, exit with the command's status
  d=$1
  rc=$2
  head -c "$CAP" "$d/o"
  [ "$(($(wc -c < "$d/o")))" -gt "$CAP" ] && printf '\n[output truncated]'
  head -c "$CAP" "$d/e" >&2
  [ "$(($(wc -c < "$d/e")))" -gt "$CAP" ] && printf '\n[output truncated]' >&2
  [ "$rc" = 124 ] && printf '\n[timed out after %ss]' "$(cat "$d/t")" >&2
  rm -rf "$d"
  exit "$rc"
}

enter() { # <cwd>
  mkdir -p "$1" 2> /dev/null
  cd "$1" 2> /dev/null || cd /
}

case "$1" in
run)
  d=$(mktemp -d /tmp/.poordsh-run.XXXXXX)
  printf %s "$3" > "$d/t"
  enter "$2"
  timeout -k 5 "$3" sh -c "$4" < /dev/null > "$d/o" 2> "$d/e"
  finish "$d" $?
  ;;
start)
  d=$2
  w=$6
  mkdir -p "$d"
  printf %s "$4" > "$d/t"
  enter "$3"
  setsid sh -c 'timeout -k 5 "$1" sh -c "$2" < /dev/null > "$3/o" 2> "$3/e"; echo $? > "$3/rc.part"; mv "$3/rc.part" "$3/rc"' \
    runner "$4" "$5" "$d" < /dev/null > /dev/null 2>&1 &
  echo $! >> "$S/sessions"
  ;;
wait)
  d=$2
  w=$3
  ;;
alive)
  limit=$2
  shift 2
  [ $# -gt 0 ] && mkdir -p "$@"
  w=$(cat "$S/watchdog" 2> /dev/null)
  if [ -n "$w" ] && [ "$(tr '\0' '\n' < "/proc/$w/cmdline" 2> /dev/null | sed -n 4p)" = poordsh-watchdog ]; then exit 0; fi
  # No watchdog (the box was paused, which ended everything, or the last one did
  # its job): keep only the sessions that still have processes.
  for s in $(cat "$S/sessions"); do pgrep -s "$s" > /dev/null && echo "$s"; done > "$S/sessions.part"
  mv "$S/sessions.part" "$S/sessions"
  setsid sh -c "$WATCHDOG" poordsh-watchdog "$limit" "$S" < /dev/null > /dev/null 2>&1 &
  echo $! > "$S/watchdog"
  exit 0
  ;;
*)
  echo "poordsh-runner: unknown mode $1" >&2
  exit 2
  ;;
esac

i=0
while [ ! -f "$d/rc" ]; do
  [ -d "$d" ] || { echo "poordsh-runner: the command's folder is gone" >&2; exit 1; }
  if [ "$i" -ge "$w" ]; then
    echo "poordsh-runner: pending" >&2
    exit 75
  fi
  sleep 1
  i=$((i + 1))
done
finish "$d" "$(cat "$d/rc")"
