"""
CountDownLatch.py

Class found at http://tshimoda.com/?p=187
Adapted and extended by Nathan Vandervoort for CS 176B W18
"""
from multiprocessing import Condition


class CountDownLatch(object):
    def __init__(self, count=1):
        self.count = count
        self.lock = Condition()

    @property
    def done(self):
        return self.count == 0

    def count_down(self):
        self.lock.acquire()
        self.count -= 1
        if self.count <= 0:
            self.lock.notify()
        self.lock.release()

    def wait(self, sec=None):
        """ Same as Java's CountDownLatch#await() """
        self.lock.acquire()
        while self.count > 0:
            self.lock.wait(sec)
        self.lock.release()
