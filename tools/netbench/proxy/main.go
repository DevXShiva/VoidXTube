// netbench-proxy: a minimal SOCKS5 (CONNECT only, no auth) server for the netbench harness.
//
// It runs on an Android phone as the adb shell user (pushed to /data/local/tmp), listens on the
// phone's loopback only, and is reached from the PC through `adb forward tcp:18080 tcp:18080`.
// Connections leave through the phone's default network, so switching the phone's Wi-Fi off
// makes the harness measure LTE with exactly the requests it sends on Wi-Fi.
//
// DNS: Android has no /etc/resolv.conf, so names are resolved by querying -dns (default Google
// Public DNS, which forwards the client subnet so googlevideo picks an edge near the phone)
// over the phone's own network.
//
// -max-mb (off by default: the phone's LTE plan is unlimited) stops accepting new connections
// once that much traffic has gone through, as a guard against a runaway run. Totals are logged
// every 30 s and on SIGUSR1 either way.
package main

import (
	"context"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

var (
	listen  = flag.String("listen", "127.0.0.1:18080", "address to listen on (keep it on loopback)")
	dns     = flag.String("dns", "8.8.8.8:53", "DNS server used to resolve names")
	maxMB   = flag.Int64("max-mb", 0, "stop accepting connections after this many MB in total (0 = no cap; the owner's LTE plan is unlimited)")
	idle    = flag.Duration("idle", 60*time.Second, "close a tunnel after this long without traffic")
	quiet   = flag.Bool("quiet", false, "don't log each connection")
	up, down atomic.Int64
	conns    atomic.Int64
)

func main() {
	flag.Parse()
	if host, _, err := net.SplitHostPort(*listen); err != nil || !net.ParseIP(host).IsLoopback() {
		log.Fatalf("refusing to listen on %q: loopback only", *listen)
	}
	resolver := &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, network, *dns)
		},
	}
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("listening on %s, dns %s, cap %d MB", *listen, *dns, *maxMB)

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM, syscall.SIGUSR1)
	go func() {
		for s := range sig {
			report()
			if s != syscall.SIGUSR1 {
				os.Exit(0)
			}
		}
	}()
	go func() {
		for range time.Tick(30 * time.Second) {
			report()
		}
	}()

	for {
		c, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		if *maxMB > 0 && up.Load()+down.Load() > *maxMB<<20 {
			log.Printf("data cap of %d MB reached, refusing %s", *maxMB, c.RemoteAddr())
			c.Close()
			continue
		}
		go handle(c, resolver)
	}
}

func report() {
	log.Printf("totals: conns=%d up=%s down=%s", conns.Load(), mb(up.Load()), mb(down.Load()))
}

func mb(n int64) string { return fmt.Sprintf("%.2fMB", float64(n)/(1<<20)) }

func handle(c net.Conn, resolver *net.Resolver) {
	defer c.Close()
	c.SetDeadline(time.Now().Add(30 * time.Second))
	target, err := handshake(c)
	if err != nil {
		log.Printf("handshake: %v", err)
		return
	}
	// Dial the name, not one resolved address: net.Dialer tries every address the resolver
	// returns and races IPv6 against IPv4 (RFC 6555, 300 ms), the way a phone's own stack does.
	// Dialing only the first address made one unreachable carrier-hosted googlevideo edge
	// (IPv6) look like a network failure in the first LTE sweep.
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	start := time.Now()
	d := net.Dialer{Resolver: resolver}
	r, err := d.DialContext(ctx, "tcp", target)
	if err != nil {
		reply(c, 5) // connection refused
		log.Printf("dial %s: %v", target, err)
		return
	}
	addr := r.RemoteAddr().String()
	defer r.Close()
	if err := reply(c, 0); err != nil {
		return
	}
	c.SetDeadline(time.Time{})
	conns.Add(1)

	var u, dn int64
	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); u = pipe(r, c, &up) }()
	go func() { defer wg.Done(); dn = pipe(c, r, &down) }()
	wg.Wait()
	if !*quiet {
		log.Printf("%s via %s: up=%d down=%d in %s", target, addr, u, dn, time.Since(start).Round(time.Millisecond))
	}
}

// pipe copies src to dst until EOF or idle timeout, counting bytes, then half-closes dst.
func pipe(dst, src net.Conn, total *atomic.Int64) int64 {
	buf := make([]byte, 32<<10)
	var n int64
	for {
		src.SetReadDeadline(time.Now().Add(*idle))
		k, err := src.Read(buf)
		if k > 0 {
			if _, werr := dst.Write(buf[:k]); werr != nil {
				break
			}
			n += int64(k)
			total.Add(int64(k))
		}
		if err != nil {
			break
		}
	}
	if tc, ok := dst.(*net.TCPConn); ok {
		tc.CloseWrite()
	} else {
		dst.Close()
	}
	return n
}

func handshake(c net.Conn) (string, error) {
	var hdr [2]byte
	if _, err := io.ReadFull(c, hdr[:]); err != nil {
		return "", err
	}
	if hdr[0] != 5 {
		return "", errors.New("not SOCKS5")
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(c, methods); err != nil {
		return "", err
	}
	if _, err := c.Write([]byte{5, 0}); err != nil { // no authentication
		return "", err
	}
	var req [4]byte
	if _, err := io.ReadFull(c, req[:]); err != nil {
		return "", err
	}
	if req[1] != 1 { // CONNECT only
		reply(c, 7)
		return "", fmt.Errorf("unsupported command %d", req[1])
	}
	var host string
	switch req[3] {
	case 1:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(c, ip); err != nil {
			return "", err
		}
		host = net.IP(ip).String()
	case 4:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(c, ip); err != nil {
			return "", err
		}
		host = net.IP(ip).String()
	case 3:
		var l [1]byte
		if _, err := io.ReadFull(c, l[:]); err != nil {
			return "", err
		}
		name := make([]byte, l[0])
		if _, err := io.ReadFull(c, name); err != nil {
			return "", err
		}
		host = string(name)
	default:
		reply(c, 8)
		return "", fmt.Errorf("unsupported address type %d", req[3])
	}
	var p [2]byte
	if _, err := io.ReadFull(c, p[:]); err != nil {
		return "", err
	}
	return net.JoinHostPort(host, strconv.Itoa(int(binary.BigEndian.Uint16(p[:])))), nil
}

func reply(c net.Conn, code byte) error {
	_, err := c.Write([]byte{5, code, 0, 1, 0, 0, 0, 0, 0, 0})
	return err
}
