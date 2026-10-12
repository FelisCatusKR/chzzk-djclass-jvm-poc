// loadgen opens N widget SSE streams and injects chat via POST /dev/chat at a
// fixed rate, then reports how many chat events each stream received.
package main

import (
	"bufio"
	"flag"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

func main() {
	base := flag.String("base", "http://localhost:18001", "")
	chans := flag.Int("c", 1, "channels (dev-channel-0..c-1)")
	per := flag.Int("n", 5, "SSE streams per channel")
	rate := flag.Int("rate", 5, "chat messages per second per channel")
	dur := flag.Duration("d", 60*time.Second, "")
	flag.Parse()

	var events, bytes atomic.Int64
	var wg sync.WaitGroup
	n := new(int)
	*n = *chans * *per
	ready := make(chan struct{}, *n)
	for i := 0; i < *n; i++ {
		ch := fmt.Sprintf("dev-channel-%d", i%*chans)
		wg.Add(1)
		go func() {
			defer wg.Done()
			resp, err := http.Get(*base + "/widget/" + ch + "/stream")
			if err != nil || resp.StatusCode != 200 {
				fmt.Println("stream:", err, resp)
				ready <- struct{}{}
				return
			}
			defer resp.Body.Close()
			ready <- struct{}{}
			sc := bufio.NewScanner(resp.Body)
			sc.Buffer(make([]byte, 1<<20), 1<<20)
			for sc.Scan() {
				l := sc.Text()
				bytes.Add(int64(len(l)))
				if strings.HasPrefix(l, "event: chat") {
					events.Add(1)
				}
			}
		}()
	}
	for i := 0; i < *n; i++ {
		<-ready
	}
	senders := []string{"dev-showstopper", "dev-theory", "dev-unsynced", "dev-unlinked"}
	tick := time.NewTicker(time.Second / time.Duration(*rate**chans))
	end := time.After(*dur)
	noRedirect := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	sent, failed := 0, 0
loop:
	for {
		select {
		case <-end:
			break loop
		case <-tick.C:
			v := url.Values{"channel": {fmt.Sprintf("dev-channel-%d", sent%*chans)}, "sender": {senders[sent%len(senders)]},
				"text": {fmt.Sprintf("부하 테스트 메시지 %d 안녕하세요", sent)}}
			resp, err := noRedirect.PostForm(*base+"/dev/chat", v)
			if err != nil || resp.StatusCode >= 400 {
				failed++
			}
			if resp != nil {
				resp.Body.Close()
			}
			sent++
		}
	}
	time.Sleep(time.Second)
	fmt.Printf("streams=%d sent=%d failed=%d chat-events=%d (%.1f/stream) bytes=%d\n",
		*n, sent, failed, events.Load(), float64(events.Load())/float64(*n), bytes.Load())
}
