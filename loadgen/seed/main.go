// seed adds a streamer channel row (no tokens) so the Go server's widget stream accepts dev-channel.
package main

import (
	"database/sql"
	"fmt"
	"os"

	_ "modernc.org/sqlite"
)

func main() {
	db, err := sql.Open("sqlite", "file:"+os.Args[1]+"?_pragma=busy_timeout(5000)")
	if err != nil {
		panic(err)
	}
	for i := 0; i < 50 && err == nil; i++ {
		_, err = db.Exec(fmt.Sprintf(`INSERT INTO users (chzzk_id, chzzk_nickname) VALUES ('dev-streamer-%[1]d','s%[1]d') ON CONFLICT DO NOTHING;
INSERT INTO channels (user_id, chzzk_channel_id) SELECT id, 'dev-channel-%[1]d' FROM users WHERE chzzk_id='dev-streamer-%[1]d' ON CONFLICT DO NOTHING;`, i))
	}
	if err != nil {
		panic(err)
	}
}
