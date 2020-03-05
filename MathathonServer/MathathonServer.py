"""
MathathonServer.py

created by Nathan Vandervoort
for CS 176B W18 quarter project
"""
import netifaces
import sys

from tqdm import tqdm
from urllib.request import urlopen

from math import sqrt
from operator import add, sub, mul, floordiv
from queue import Queue, Empty
from random import randint, choice
from socket import socket, AF_INET, SOCK_DGRAM, timeout, gethostbyname
from threading import Thread
from time import sleep
from types import FunctionType

from CountDownLatch import CountDownLatch


def return_with_prob(func, prob):
    """ Returns with given probability (in percent), executes func otherwise """
    if randint(0, 99) < prob:
        return
    func()


class Headers:
    NEW_GAME = "NEW_GAME"
    START_GAME = "START_GAME"
    LEAVE_GAME = "LEAVE_GAME"
    QUESTIONS = "QUESTIONS"
    ANSWER = "ANSWER"
    IS_HOSTING = "HOSTING"
    GAME_OVER = "GAME_OVER"


class MathathonServer:
    COUNTDOWN_LENGTH = 5
    DROP_RATE = 39
    TIME_LIMIT_MULTIPLIER = 4  # orig 4

    class MathathonGame:
        """ Generates questions and converts them to packet form """
        def __init__(self, num_questions, difficulty):
            self.num_questions = num_questions
            self.difficulty = difficulty
            self.questions = []
            self.generate_questions()
            self.time_limit = num_questions * (2 if difficulty == 1 else 3) * MathathonServer.TIME_LIMIT_MULTIPLIER

        def packet_format(self):
            return "{}{}|{}|{}|{}".format(Headers.QUESTIONS, self.num_questions,
                                          self.difficulty, self.time_limit,
                                          ",".join(self.questions))

        @staticmethod
        def is_prime(n):
            if n == 2:
                return True
            if n % 2 == 0 and n != 2:
                return False
            return all(n % i for i in range(3, int(sqrt(n)) + 1, 2))

        def generate_questions(self):
            def generate_terms(op):
                if op == add:
                    first = randint(0, 400)
                    second = randint(0, 400)
                elif op == sub:
                    first = randint(10, 800)
                    second = randint(10, first)
                elif op == mul:
                    first = randint(0, 30)
                    second = randint(0, 30)
                else:
                    first = randint(40, 200)
                    while MathathonServer.MathathonGame.is_prime(first):
                        first = randint(40, 200)
                    second = first
                    while first % second != 0 or first == second:
                        second = randint(10, 100)
                if first < second:
                    temp = first
                    first = second
                    second = temp
                return first, second

            choices = [add, sub]
            if self.difficulty == 2:
                choices += [mul, floordiv]

            for i in range(self.num_questions):
                op = choice(choices)
                first, second = generate_terms(op)
                self.questions.append("{}:{}:{}:{}".format(
                    first, second,
                    {add: '+', sub: '-', mul: '*', floordiv: '/'}[op], op(first, second)))

    class PerformanceSnapshot:
        """ Keeps track of players' current performance and updates it accordingly """
        def __init__(self):
            self.num_completed = 0
            self.num_answered = 0
            self.num_correct = 0

        def update_vals(self, completed, answered, correct):
            if self.num_completed < completed:
                self.num_completed = completed
            if self.num_answered < answered:
                self.num_answered = answered
            if self.num_correct < correct:
                self.num_correct = correct

    def __init__(self, port, num_questions, difficulty):
        """
        :param (int) port:
        :param (int) num_questions:
        :param (int) difficulty:
        """
        self.socket = socket(AF_INET, SOCK_DGRAM)
        self.port = port
        try:  # if available guaranteed to work
            self.inet_addr = netifaces.ifaddresses('en0')[netifaces.AF_INET][0]['addr']
        except (KeyError, IndexError):
            self.inet_addr = urlopen('http://ip.42.pl/raw').read().decode()

        self.socket.bind((gethostbyname('0.0.0.0'), port))
        self.clients = dict()
        self.clients_performance = dict()
        self.game = MathathonServer.MathathonGame(num_questions, difficulty)
        self.waiting = True
        self.answer_queue = Queue()
        self.time_left = 0

    def __del__(self):
        self.socket.close()

    def wait_for_packet(self, timeout_val=None, *headers):
        """ Waits for packet or packets with given headers for timeout_val before raising exception
        :return: received address, qualifying packets received"""
        self.socket.settimeout(timeout_val)
        packets = []
        try:
            while not packets:
                msg, addr = self.socket.recvfrom(1024)
                packets = [p for p in msg.decode().split(';')[:-1] if
                           not headers or any((p.startswith(h) for h in headers))]
                if packets:
                    return addr, packets
        except timeout as e:
            raise e
        except BlockingIOError:
            return "", packets
        finally:
            self.socket.settimeout(None)

    def wait_for_confirmation(self, frequency, retransmission, accepted_header, error_header=None, confirmation=lambda *args: None):
        """
        Waits for confirmations of packets (checking header) and retransmits with given frequency if necessary.
        Executes confirmation when proper packet received
        Returns addr for error if error_header was found
        :param (float) frequency:
        :param (FunctionType) retransmission: must take an iterable of addresses
        :param (str) accepted_header:
        :param (str) error_header:
        :param (FunctionType) confirmation:
        """
        responses = set()
        while responses != self.clients.keys():
            retransmission(self.clients.keys() - responses)
            try:
                addr, packets = self.wait_for_packet(frequency, accepted_header, error_header)
            except timeout:
                pass
            else:
                if addr not in self.clients:
                    continue
                if error_header is not None and any([p.startswith(error_header) for p in packets]):
                    return addr
                if addr not in responses:  # avoid repeated confirmations
                    confirmation()
                responses.add(addr)

    def run(self):
        t = Thread(target=self.await_clients, daemon=True)
        t.start()
        interrupt = input("Server started at {}:{}, clients are joining. "
                          "Enter exit or start at any time.\n".format(self.inet_addr, self.port))
        while interrupt.lower() not in ("start", "exit"):
            print("Please enter \"exit\" or \"start\"")
            interrupt = input("Enter exit or start at any time.\n")
        self.waiting = False
        t.join()
        if interrupt.lower() == "start":
            self.begin_game()

    def await_clients(self):
        """ Wait for clients to join game """

        while self.waiting:
            try:
                addr, packets = self.wait_for_packet(.5, Headers.NEW_GAME,
                                                     Headers.IS_HOSTING,
                                                     Headers.LEAVE_GAME)
                for packet in packets:
                    if packet.startswith(Headers.NEW_GAME):
                        name = packet.split('-')[1]
                        if addr not in self.clients:
                            print("Client joined with name " + name)
                            self.clients[addr] = name
                        self.socket.sendto(packet.encode(), addr)
                    elif packet.startswith(Headers.IS_HOSTING):
                        self.socket.sendto(packet.encode(), addr)
                    elif packet.startswith(Headers.LEAVE_GAME):
                        print("Player with name {} has left the game".format(self.clients[addr]))
                        self.clients.pop(addr, None)
            except timeout:  # used to check periodically if still waiting
                pass

    def begin_game(self):
        if len(self.clients) == 0:
            print("No players joined. Exiting program.")
            return
        for addr in self.clients.keys():
            self.clients_performance[addr] = MathathonServer.PerformanceSnapshot()
        self.time_left = self.game.time_limit

        print("Beginning game with {} players:\n\t{}"
              .format(len(self.clients), ", ".join(sorted(self.clients.values()))))
        print("Game is {} questions long with {} difficulty. Time limit is {} seconds"
              .format(len(self.game.questions),
                      "easy" if self.game.difficulty == 1 else "hard",
                      self.game.time_limit))
        print("Sending game information to players")

        self.send_info_await_confirmation()
        print("Counting down...")
        sleep(MathathonServer.COUNTDOWN_LENGTH + 1)
        self.clients_complete = CountDownLatch(len(self.clients))
        Thread(target=self.display_timer, daemon=True).start()
        Thread(target=self.process_answers, daemon=True).start()
        for offset, (addr, name) in enumerate(self.clients.items()):
            Thread(target=self.monitor_client, args=(addr, name, offset)).start()
        self.clients_complete.wait()
        print("\n" * (len(self.clients) - (1 if self.time_left == 0 else 0)))
        self.display_results()

    def send_info_await_confirmation(self):
        """ Send questions and wait for confirmation. Resend if necessary. """
        responses = set()

        def send_info_to_clients():
            for addr in self.clients.keys() - responses:
                self.socket.sendto(self.game.packet_format().encode(), addr)

        bar_fmt = "Awaiting confirmation from players: {n_fmt}/{total_fmt} players confirmed"
        with tqdm(total=len(self.clients), ncols=100, bar_format=bar_fmt) as pbar:
            while responses != self.clients.keys():
                return_with_prob(send_info_to_clients, MathathonServer.DROP_RATE)
                try:
                    addr, packets = self.wait_for_packet(1, Headers.START_GAME,
                                                         Headers.LEAVE_GAME)
                except timeout:
                    pass
                else:
                    for p in packets:
                        if p.startswith(Headers.START_GAME):
                            responses.add(addr)
                            pbar.update(1)
                        elif p.startswith(Headers.LEAVE_GAME and addr in self.clients):
                            print("Player with name {} left, exiting program".format(
                                self.clients[addr]))
                            exit(0)

        # try to ensure that all clients receive start game at nearly same time and at all
        for _ in range(3):
            for addr in self.clients:
                self.socket.sendto(Headers.START_GAME.encode(), addr)
            sleep(.1)

    def display_timer(self):
        bar_fmt = "Time elapsed: {n_fmt}/{total_fmt} seconds"
        for _ in tqdm(range(self.game.time_limit), ncols=100, position=0, bar_format=bar_fmt):
            if self.clients_complete.done:
                break
            sleep(1)
            if self.clients_complete.done:
                break
            self.time_left -= 1

    def process_answers(self):
        """
        Processes answers by updating performances, confirming receipt, and putting
        data in the queue for the progress bars to read and update.
        Structure for answer packets is `ANSWER-num_completed-num_answered-num_correct;`
        """
        while not self.clients_complete.done:
            try:
                addr, packets = self.wait_for_packet(.5, Headers.ANSWER)
            except timeout:
                pass
            except BlockingIOError:
                break
            else:
                for packet in packets:
                    self.clients_performance[addr].update_vals(*map(int, packet.split('-')[1:]))
                    num_completed = self.clients_performance[addr].num_completed
                    self.answer_queue.put((addr, num_completed))
                    self.socket.sendto("{}-{}".format(Headers.ANSWER, num_completed).encode(), addr)

    def monitor_client(self, addr, name, offset):
        """ Updates client progress bar and keeps track of whether client has finished """

        num_questions_complete = 0
        name_heading = name if len(name) < 14 else name[:15] + "..."
        bar_fmt = "%-18s|{bar}| {n_fmt}/{total_fmt} questions complete" % name_heading
        with tqdm(total=self.game.num_questions, ncols=100, position=offset+1,
                  bar_format=bar_fmt) as pbar:
            while num_questions_complete < self.game.num_questions and self.time_left > 0:
                try:  # use try and timeout to ensure it does get stuck waiting forever
                    recvd_addr, new_num_complete = self.answer_queue.get(timeout=1)
                    if recvd_addr == addr:
                        pbar.update(new_num_complete - num_questions_complete)
                        num_questions_complete = self.clients_performance[addr].num_completed
                    else:
                        self.answer_queue.put((recvd_addr, new_num_complete))
                    self.answer_queue.task_done()
                except Empty:
                    pass
        self.clients_complete.count_down()

    def display_results(self):
        print("Game over, results as follows:")
        sorted_perf_items = sorted(self.clients_performance.items(),
                                   key=lambda x: x[1].num_correct,
                                   reverse=True)
        for addr, perf in sorted_perf_items:
            print("\tPlayer {} answered {} questions total with {} correct".format(
                self.clients[addr], perf.num_answered, perf.num_correct
            ))
        winning_num = sorted_perf_items[0][1].num_correct

        winning_addrs = [addr for addr, perf in sorted_perf_items if perf.num_correct == winning_num]
        losing_addrs = [addr for addr, perf in sorted_perf_items if perf.num_correct != winning_num]

        winners_names = [self.clients[addr] for addr in winning_addrs]

        # formats winners as comma-separated list
        print("Congratulations {}{}!".format(
            "".join([name + (", " if len(winners_names) > 2 else " ")
                     for name in winners_names[:-1]]),
            ("and " if len(winners_names) > 1 else "") + winners_names[-1]))

        responses = set()

        def send_results():
            for winner in set(winning_addrs) - responses:
                self.socket.sendto("{}-{}".format(Headers.GAME_OVER, 'w').encode(), winner)
            for loser in set(losing_addrs) - responses:
                self.socket.sendto("{}-{}".format(Headers.GAME_OVER, 'l').encode(), loser)

        while responses != self.clients.keys():
            return_with_prob(send_results, MathathonServer.DROP_RATE)
            try:
                addr, packets = self.wait_for_packet(2, Headers.GAME_OVER)
            except timeout:
                pass
            else:
                responses.add(addr)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python3 MathathonServer.py port num_questions difficulty[1-2]")
        exit(1)

    if len(sys.argv[1]) != 4:
        print("Four-digit ports only, please try again")
        exit(2)

    try:
        port = int(sys.argv[1])
        num_questions = int(sys.argv[2])
        diff = int(sys.argv[3])
    except ValueError:
        print("Error parsing parameters, use integer values")
        exit(2)
    else:
        if diff not in (1, 2):
            print("Please choose a difficulty of either 1 or 2")
            exit(2)

        MathathonServer(port, num_questions, diff).run()
