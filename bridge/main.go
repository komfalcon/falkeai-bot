package main

import (
	"bufio"
	"context"
	"encoding/json"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

var stdoutMu sync.Mutex
var botReadMu sync.Mutex
var botMarkedReadChats = make(map[string]time.Time)
var botMarkedReadMsgs = make(map[string]time.Time)

func recordBotMarkRead(chatJID string, msgID string) {
	botReadMu.Lock()
	defer botReadMu.Unlock()
	now := time.Now()
	if chatJID != "" {
		botMarkedReadChats[chatJID] = now
	}
	if msgID != "" {
		botMarkedReadMsgs[msgID] = now
	}
	for k, t := range botMarkedReadChats {
		if now.Sub(t) > 30*time.Second {
			delete(botMarkedReadChats, k)
		}
	}
	for k, t := range botMarkedReadMsgs {
		if now.Sub(t) > 30*time.Second {
			delete(botMarkedReadMsgs, k)
		}
	}
}

func wasMarkedReadByBot(chatJID string, msgIDs []types.MessageID) bool {
	botReadMu.Lock()
	defer botReadMu.Unlock()
	now := time.Now()
	if t, ok := botMarkedReadChats[chatJID]; ok && now.Sub(t) < 10*time.Second {
		return true
	}
	for _, id := range msgIDs {
		if t, ok := botMarkedReadMsgs[string(id)]; ok && now.Sub(t) < 15*time.Second {
			return true
		}
	}
	return false
}

func emit(payload map[string]any) {
	stdoutMu.Lock()
	defer stdoutMu.Unlock()
	b, err := json.Marshal(payload)
	if err != nil {
		return
	}
	os.Stdout.Write(append(b, '\n'))
}

type BridgeCommand struct {
	Cmd            string   `json:"cmd"`
	ChatJID        string   `json:"chat_jid"`
	GroupJID       string   `json:"group_jid"`
	ParticipantJID string   `json:"participant_jid"`
	SenderJID      string   `json:"sender_jid"`
	MessageID      string   `json:"message_id"`
	RequestID      string   `json:"request_id"`
	Text           string   `json:"text"`
	State          string   `json:"state"`
	Mentions       []string `json:"mentions"`
}

func cleanPhone(raw string) string {
	var b strings.Builder
	for _, r := range raw {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	digits := b.String()
	if len(digits) == 11 && strings.HasPrefix(digits, "0") {
		digits = "234" + digits[1:]
	}
	return digits
}

func extractSimpleText(msg *waE2E.Message) string {
	if msg == nil {
		return ""
	}
	if msg.GetConversation() != "" {
		return strings.TrimSpace(msg.GetConversation())
	}
	if ext := msg.GetExtendedTextMessage(); ext != nil {
		return strings.TrimSpace(ext.GetText())
	}
	if img := msg.GetImageMessage(); img != nil {
		return strings.TrimSpace(img.GetCaption())
	}
	if vid := msg.GetVideoMessage(); vid != nil {
		return strings.TrimSpace(vid.GetCaption())
	}
	if doc := msg.GetDocumentMessage(); doc != nil {
		return strings.TrimSpace(doc.GetCaption())
	}
	return ""
}

func extractQuotedText(msg *waE2E.Message) string {
	if msg == nil {
		return ""
	}
	var ci *waE2E.ContextInfo
	if ext := msg.GetExtendedTextMessage(); ext != nil {
		ci = ext.GetContextInfo()
	} else if img := msg.GetImageMessage(); img != nil {
		ci = img.GetContextInfo()
	} else if vid := msg.GetVideoMessage(); vid != nil {
		ci = vid.GetContextInfo()
	} else if aud := msg.GetAudioMessage(); aud != nil {
		ci = aud.GetContextInfo()
	}
	if ci == nil {
		return ""
	}
	q := extractSimpleText(ci.GetQuotedMessage())
	runes := []rune(q)
	if len(runes) > 220 {
		q = string(runes[:220])
	}
	return q
}

func unwrapEdited(msg *waE2E.Message) *waE2E.Message {
	if msg == nil {
		return nil
	}
	if pm := msg.GetProtocolMessage(); pm != nil {
		if edited := pm.GetEditedMessage(); edited != nil {
			return edited
		}
	}
	return msg
}

func saveTempMedia(data []byte) string {
	// Sweep stale media files older than 10 minutes
	if matches, err := filepath.Glob(filepath.Join(os.TempDir(), "falkeai_media_*")); err == nil {
		cutoff := time.Now().Add(-10 * time.Minute)
		for _, m := range matches {
			if info, err := os.Stat(m); err == nil && info.ModTime().Before(cutoff) {
				_ = os.Remove(m)
			}
		}
	}
	f, err := os.CreateTemp("", "falkeai_media_*")
	if err != nil {
		return ""
	}
	path := f.Name()
	if _, err := f.Write(data); err != nil {
		_ = f.Close()
		_ = os.Remove(path)
		return ""
	}
	_ = f.Close()
	return path
}

func extractTextAndURLs(msg *waE2E.Message) (string, bool, []string, bool) {
	urls := make([]string, 0)
	msg = unwrapEdited(msg)
	if msg == nil {
		return "", false, urls, false
	}
	if msg.GetConversation() != "" {
		return strings.TrimSpace(msg.GetConversation()), false, urls, true
	}
	if ext := msg.GetExtendedTextMessage(); ext != nil {
		if ext.GetMatchedText() != "" {
			urls = append(urls, ext.GetMatchedText())
		}
		return strings.TrimSpace(ext.GetText()), false, urls, true
	}
	if img := msg.GetImageMessage(); img != nil {
		return strings.TrimSpace(img.GetCaption()), false, urls, true
	}
	if vid := msg.GetVideoMessage(); vid != nil {
		return strings.TrimSpace(vid.GetCaption()), false, urls, true
	}
	if doc := msg.GetDocumentMessage(); doc != nil {
		return strings.TrimSpace(doc.GetCaption()), false, urls, true
	}
	if msg.GetAudioMessage() != nil {
		return "", true, urls, true
	}
	if msg.GetStickerMessage() != nil {
		return "", false, urls, true
	}
	return "", false, urls, false
}

func main() {
	ctx := context.Background()
	dbPath := os.Getenv("WA_SESSION_DB")
	if dbPath == "" {
		dbPath = "file:wa_session.db?_foreign_keys=on&_journal_mode=DELETE&_synchronous=FULL"
	}

	container, err := sqlstore.New(ctx, "sqlite3", dbPath, waLog.Noop)
	if err != nil {
		emit(map[string]any{"event": "error", "message": "sqlstore init failed: " + err.Error()})
		os.Exit(1)
	}

	deviceStore, err := container.GetFirstDevice(ctx)
	if err != nil {
		emit(map[string]any{"event": "error", "message": "get device failed: " + err.Error()})
		os.Exit(1)
	}

	client := whatsmeow.NewClient(deviceStore, waLog.Noop)

	client.AddEventHandler(func(evt any) {
		switch v := evt.(type) {
		case *events.Connected:
			selfJID := ""
			selfLID := ""
			if client.Store.ID != nil {
				selfJID = client.Store.ID.ToNonAD().String()
			}
			if !client.Store.LID.IsEmpty() {
				selfLID = client.Store.LID.ToNonAD().String()
			}
			emit(map[string]any{
				"event":    "connected",
				"self_jid": selfJID,
				"self_lid": selfLID,
			})
			// Sync saved contacts from whatsmeow store
			if contacts, err := client.Store.Contacts.GetAllContacts(ctx); err == nil {
				list := make([]map[string]any, 0, len(contacts))
				for jid, info := range contacts {
					name := strings.TrimSpace(info.FullName)
					if name == "" {
						name = strings.TrimSpace(info.FirstName)
					}
					if name != "" {
						list = append(list, map[string]any{
							"jid":  jid.String(),
							"name": name,
						})
					}
				}
				if len(list) > 0 {
					emit(map[string]any{
						"event":    "contacts_sync",
						"contacts": list,
					})
				}
			}

		case *events.Message:
			unwrapped := unwrapEdited(v.Message)
			text, isVoice, matchedURLs, isUserMsg := extractTextAndURLs(unwrapped)
			if !isUserMsg {
				return
			}
			quotedText := extractQuotedText(unwrapped)
			mediaKind := ""
			mediaPath := ""
			mediaMime := ""
			if !v.Info.IsGroup && !v.Info.IsFromMe && unwrapped != nil {
				if aud := unwrapped.GetAudioMessage(); aud != nil && aud.GetFileLength() <= 5*1024*1024 {
					if data, err := client.Download(ctx, aud); err == nil && len(data) > 0 && len(data) <= 5*1024*1024 {
						if p := saveTempMedia(data); p != "" {
							mediaKind = "audio"
							mediaPath = p
							mediaMime = aud.GetMimetype()
						}
					}
				} else if img := unwrapped.GetImageMessage(); img != nil && img.GetFileLength() <= 4*1024*1024 {
					if data, err := client.Download(ctx, img); err == nil && len(data) > 0 && len(data) <= 4*1024*1024 {
						if p := saveTempMedia(data); p != "" {
							mediaKind = "image"
							mediaPath = p
							mediaMime = img.GetMimetype()
						}
					}
				}
			}
			senderAlt := ""
			if !v.Info.SenderAlt.IsEmpty() {
				senderAlt = v.Info.SenderAlt.ToNonAD().String()
			}
			var chatAlt any = nil
			if !v.Info.IsGroup {
				if v.Info.IsFromMe && !v.Info.RecipientAlt.IsEmpty() {
					chatAlt = v.Info.RecipientAlt.ToNonAD().String()
				} else if !v.Info.IsFromMe && !v.Info.SenderAlt.IsEmpty() {
					chatAlt = v.Info.SenderAlt.ToNonAD().String()
				}
			}
			pushName := strings.TrimSpace(v.Info.PushName)
			if !v.Info.IsFromMe && client.Store != nil && client.Store.Contacts != nil {
				if c, err := client.Store.Contacts.GetContact(ctx, v.Info.Chat.ToNonAD()); err == nil && c.Found {
					if strings.TrimSpace(c.FullName) != "" {
						pushName = strings.TrimSpace(c.FullName)
					} else if strings.TrimSpace(c.FirstName) != "" {
						pushName = strings.TrimSpace(c.FirstName)
					}
				}
			}
			var quotedAny any = nil
			if quotedText != "" {
				quotedAny = quotedText
			}
			var mkAny, mpAny, mmAny any = nil, nil, nil
			if mediaKind != "" && mediaPath != "" {
				mkAny = mediaKind
				mpAny = mediaPath
				if mediaMime != "" {
					mmAny = mediaMime
				}
			}
			emit(map[string]any{
				"event":          "message",
				"id":             v.Info.ID,
				"chat_jid":       v.Info.Chat.ToNonAD().String(),
				"chat_alt_jid":   chatAlt,
				"sender_jid":     v.Info.Sender.ToNonAD().String(),
				"sender_alt_jid": senderAlt,
				"push_name":      pushName,
				"from_me":        v.Info.IsFromMe,
				"is_group":       v.Info.IsGroup,
				"timestamp":      v.Info.Timestamp.Unix(),
				"text":           text,
				"quoted_text":    quotedAny,
				"is_voice":       isVoice,
				"media_kind":     mkAny,
				"media_path":     mpAny,
				"media_mime":     mmAny,
				"matched_urls":   matchedURLs,
			})

		case *events.GroupInfo:
			emit(map[string]any{
				"event":     "group_invalidate",
				"group_jid": v.JID.ToNonAD().String(),
			})

		case *events.Receipt:
			if !v.IsGroup && (v.Type == types.ReceiptTypeReadSelf || (v.Type == types.ReceiptTypeRead && v.IsFromMe)) {
				chatJID := v.Chat.ToNonAD().String()
				if chatJID != "" && !wasMarkedReadByBot(chatJID, v.MessageIDs) {
					emit(map[string]any{
						"event":        "chat_read_by_owner",
						"chat_jid":     chatJID,
						"chat_alt_jid": nil,
					})
				}
			}

		case *events.MarkChatAsRead:
			if v.Action != nil && v.Action.GetRead() {
				chatJID := v.JID.ToNonAD().String()
				if chatJID != "" && !strings.HasSuffix(chatJID, "@g.us") && !wasMarkedReadByBot(chatJID, nil) {
					emit(map[string]any{
						"event":        "chat_read_by_owner",
						"chat_jid":     chatJID,
						"chat_alt_jid": nil,
					})
				}
			}

		case *events.LoggedOut:
			emit(map[string]any{"event": "logged_out"})
			os.Exit(1)
		}
	})

	if client.Store.ID == nil {
		qrChan, _ := client.GetQRChannel(ctx)
		if err := client.Connect(); err != nil {
			emit(map[string]any{"event": "error", "message": "connect failed: " + err.Error()})
			os.Exit(1)
		}
		pairPhone := cleanPhone(os.Getenv("PAIR_PHONE"))
		if pairPhone == "" {
			pairPhone = cleanPhone(os.Getenv("OWNER_PHONE"))
		}
		if pairPhone != "" {
			go func() {
				paired := false
				for evt := range qrChan {
					if evt.Event == "code" && !paired {
						paired = true
						code, err := client.PairPhone(ctx, pairPhone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
						if err != nil {
							emit(map[string]any{"event": "error", "message": "pair code failed for " + pairPhone + ": " + err.Error()})
						} else {
							emit(map[string]any{"event": "pairing_code", "code": code + " (Phone: +" + pairPhone + ")"})
						}
					} else if evt.Event == "timeout" || evt.Event == "err-client-outdated" {
						emit(map[string]any{"event": "error", "message": "Pairing code expired, requesting a fresh code..."})
						os.Exit(1)
					}
				}
			}()
		} else {
			emit(map[string]any{"event": "error", "message": "Not linked yet. Set PAIR_PHONE and restart."})
		}
	} else {
		if err := client.Connect(); err != nil {
			emit(map[string]any{"event": "error", "message": "connect failed: " + err.Error()})
			os.Exit(1)
		}
	}

	// Read commands from Rust Core over stdin
	go func() {
		scanner := bufio.NewScanner(os.Stdin)
		scanner.Buffer(make([]byte, 1024*64), 1024*1024)
		for scanner.Scan() {
			var cmd BridgeCommand
			if err := json.Unmarshal(scanner.Bytes(), &cmd); err != nil {
				continue
			}
			handleCommand(ctx, client, cmd)
		}
		os.Exit(0)
	}()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	<-sig
	client.Disconnect()
}

func handleCommand(ctx context.Context, client *whatsmeow.Client, cmd BridgeCommand) {
	switch cmd.Cmd {
	case "send_message":
		chatJID, err := types.ParseJID(cmd.ChatJID)
		if err != nil {
			return
		}
		msg := &waE2E.Message{Conversation: proto.String(cmd.Text)}
		if len(cmd.Mentions) > 0 {
			msg = &waE2E.Message{
				ExtendedTextMessage: &waE2E.ExtendedTextMessage{
					Text: proto.String(cmd.Text),
					ContextInfo: &waE2E.ContextInfo{
						MentionedJID: cmd.Mentions,
					},
				},
			}
		}
		_, _ = client.SendMessage(ctx, chatJID, msg)

	case "send_presence":
		chatJID, err := types.ParseJID(cmd.ChatJID)
		if err != nil {
			return
		}
		state := types.ChatPresenceComposing
		if cmd.State == "paused" {
			state = types.ChatPresencePaused
		}
		_ = client.SendChatPresence(ctx, chatJID, state, types.ChatPresenceMediaText)

	case "mark_read":
		chatJID, err := types.ParseJID(cmd.ChatJID)
		if err != nil {
			return
		}
		recordBotMarkRead(chatJID.ToNonAD().String(), cmd.MessageID)
		var senderJID types.JID
		if cmd.SenderJID != "" {
			senderJID, _ = types.ParseJID(cmd.SenderJID)
		}
		_ = client.MarkRead(ctx, []types.MessageID{cmd.MessageID}, time.Now(), chatJID, senderJID)

	case "delete_message":
		groupJID, err1 := types.ParseJID(cmd.GroupJID)
		senderJID, err2 := types.ParseJID(cmd.SenderJID)
		if err1 != nil || err2 != nil {
			return
		}
		revokeMsg := client.BuildRevoke(groupJID, senderJID, cmd.MessageID)
		_, _ = client.SendMessage(ctx, groupJID, revokeMsg)

	case "remove_participant":
		groupJID, err1 := types.ParseJID(cmd.GroupJID)
		partJID, err2 := types.ParseJID(cmd.ParticipantJID)
		if err1 != nil || err2 != nil {
			return
		}
		_, _ = client.UpdateGroupParticipants(ctx, groupJID, []types.JID{partJID}, whatsmeow.ParticipantChangeRemove)

	case "fetch_group_roster":
		groupJID, err := types.ParseJID(cmd.GroupJID)
		if err != nil {
			return
		}
		info, err := client.GetGroupInfo(ctx, groupJID)
		if err != nil {
			emit(map[string]any{"event": "error", "message": "GetGroupInfo failed: " + err.Error()})
			return
		}
		participants := make([]map[string]any, 0, len(info.Participants))
		for _, p := range info.Participants {
			lid := ""
			if !p.LID.IsEmpty() {
				lid = p.LID.ToNonAD().String()
			}
			pn := ""
			if !p.PhoneNumber.IsEmpty() {
				pn = p.PhoneNumber.ToNonAD().String()
			}
			participants = append(participants, map[string]any{
				"jid":          p.JID.ToNonAD().String(),
				"lid":          lid,
				"phone_number": pn,
				"is_admin":     p.IsAdmin || p.IsSuperAdmin,
			})
		}
		linkedParent := ""
		if !info.LinkedParentJID.IsEmpty() {
			linkedParent = info.LinkedParentJID.ToNonAD().String()
		}
		emit(map[string]any{
			"event":         "group_roster",
			"request_id":    cmd.RequestID,
			"group_jid":     cmd.GroupJID,
			"subject":       info.Name,
			"description":   info.Topic,
			"linked_parent": linkedParent,
			"participants":  participants,
		})

	case "fetch_joined_groups":
		groups, err := client.GetJoinedGroups(ctx)
		if err != nil {
			return
		}
		out := make([]map[string]any, 0, len(groups))
		for _, g := range groups {
			out = append(out, map[string]any{
				"jid":     g.JID.ToNonAD().String(),
				"subject": g.Name,
			})
		}
		emit(map[string]any{
			"event":      "groups_list",
			"request_id": cmd.RequestID,
			"groups":     out,
		})
	}
}
