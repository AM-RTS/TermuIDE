import io
import json
import pathlib
import queue
import unittest

from benchmark import Client, position, read_frame


class ProtocolChecks(unittest.TestCase):
    def test_unicode_frames_use_bytes_and_keep_the_next_message(self):
        message = {'jsonrpc': '2.0', 'method': 'fixture', 'params': {'text': '🧪é'}}
        payload = json.dumps(message, ensure_ascii=False).encode()
        frame = f'Content-Length: {len(payload)}\r\n\r\n'.encode() + payload
        stream = io.BytesIO(frame + frame)
        self.assertEqual(message, read_frame(stream))
        self.assertEqual(message, read_frame(stream))

    def test_malformed_and_truncated_server_output_is_rejected(self):
        for data in [b'Content-Length: 4\r\n\r\n{}', b'Content-Length: -1\r\n\r\n',
                     b'Content-Length: 999999999\r\n\r\n', b'Content-Length: 2\n\n{}',
                     b'Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}']:
            with self.subTest(data=data), self.assertRaises((ValueError, EOFError)):
                read_frame(io.BytesIO(data))

    def test_server_requests_do_not_consume_client_responses_with_the_same_id(self):
        client = Client.__new__(Client)
        client.events, client.responses, client.diagnostics = queue.Queue(), {}, {}
        client.settings = {'java': {'project': {'sourcePaths': ['src']}}}
        client.root = pathlib.Path('/fixture')
        client.log = io.StringIO()
        sent = []
        client.send = sent.append
        client.events.put({'id': 1, 'method': 'workspace/configuration',
                           'params': {'items': [{'section': 'java.project'}, {'section': 'unknown'}]}})
        client.events.put({'id': 1, 'result': {'items': [{'label': 'substring'}]}})
        client.pump(1)
        self.assertEqual([{'id': 1, 'result': [{'sourcePaths': ['src']}, {}]}], sent)
        self.assertEqual({}, client.responses)
        client.pump(1)
        self.assertEqual({'items': [{'label': 'substring'}]}, client.responses[1]['result'])

    def test_java_caret_positions_count_utf16_units_after_supplementary_characters(self):
        self.assertEqual({'line': 1, 'character': 4}, position('first\n🧪ab;', len('first\n🧪ab')))


if __name__ == '__main__':
    unittest.main()
