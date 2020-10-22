import os
import time
import traceback
from functools import wraps
from random import randint
from threading import local

import Python.timbermill.timberlog_consts as consts
from Python.timbermill import timberlog_event_handler
from Python.timbermill.optional_args_decorator import optional_args_decorator
from Python.timbermill.stack import Stack

thread_instance = local()


def init(timbermill_hostname, logger=None):
    timberlog_event_handler.init(timbermill_hostname, logger)


def start_task(name, retention_days=None):
    return TimberLogContext(name, retention_days)


class TimberLogContext:

    def __init__(self, name, retention_days=None):
        self.name = name
        self.retention_days = retention_days

    def __enter__(self):
        start(self.name, retention_days=self.retention_days)
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        if exc_type is None and exc_val is None and exc_tb is None:
            success()
            return True
        else:
            end_with_error()
            return False


def start(name, parent_id=None, retention_days=None):
    event, task_id, parent_id = __handle_new_task(name, consts.EVENT_TYPE_START, parent_id=parent_id, retention_days=retention_days)
    stack = __get_stack()
    stack.push((task_id, parent_id))

    return event[consts.TASK_ID]


def success():
    event = __create_end_event(consts.EVENT_TYPE_END_SUCCESS)
    timberlog_event_handler.submit_event(event)


def end_with_error(exception=None):
    if exception is None:
        exception = traceback.format_exc()

    text = {consts.EXCEPTION: str(exception)}
    event = __create_end_event(consts.EVENT_TYPE_END_ERROR, text)
    timberlog_event_handler.submit_event(event)


def spot(name, context={}, strings={}, metrics={}, text={}, parent_id=None, task_success=True):
    if task_success:
        status = consts.SUCCESS_STATUS
    else:
        status = consts.ERROR_STATUS
    event, _, _, = __handle_new_task(name, consts.EVENT_TYPE_SPOT, context, strings, metrics, text, parent_id, status=status)

    return event[consts.TASK_ID]


def add_strings(**kwargs):
    info(strings=kwargs)


def add_context(**kwargs):
    info(context=kwargs)


def add_text(**kwargs):
    info(text=kwargs)


def add_metrics(**kwargs):
    info(metrics=kwargs)


def info(context={}, strings={}, metrics={}, text={}):
    stack = __get_stack()

    if stack.is_empty():
        text[consts.STACK_TRACK] = str(traceback.format_exc())
        event = timberlog_event_handler.create_event(consts.EVENT_TYPE_SPOT, text, consts.LOG_WITHOUT_CONTEXT, context, strings, metrics)
    else:
        curr_task = stack.peek()
        task_id = curr_task[consts.TASK_ID_INDEX]
        event = timberlog_event_handler.create_event(consts.EVENT_TYPE_INFO, text, task_id=task_id, context=context, event_strings=strings, metrics=metrics)

    timberlog_event_handler.submit_event(event)


def __handle_new_task(name, event_type, context={}, strings={}, metrics={}, text={}, parent_id=None, retention_days=None, status=None):
    task_id = __generate_task_id(name)

    if not parent_id:
        parent_id = __resolve_parent_id()
    event = timberlog_event_handler.create_event(event_type, text, name, task_id, context, strings, metrics, parent_id, retention_days, status=status)
    timberlog_event_handler.submit_event(event)

    return event, task_id, parent_id


def __resolve_parent_id():
    task_id_stack = __get_stack()
    parent_id = None

    if not task_id_stack.is_empty():
        curr_task = task_id_stack.peek()
        parent_id = curr_task[consts.TASK_ID_INDEX]

    return parent_id


def __get_stack():
    __handle_forked_process()
    task_id_stack = getattr(thread_instance, 'task_id_stack', None)
    if task_id_stack is None:
        task_id_stack = thread_instance.task_id_stack = Stack()

    return task_id_stack


def __handle_forked_process():
    pid = os.getpid()
    curr_pid = getattr(thread_instance, 'process_pid', None)
    if pid != curr_pid:
        __clear_local_data()
        thread_instance.process_pid = pid

    return curr_pid


def __clear_local_data():
    task_id_stack = getattr(thread_instance, 'task_id_stack', None)
    if task_id_stack:
        task_id_stack.clear()


def __create_end_event(event_type, text={}):
    stack = __get_stack()

    if stack.is_empty():
        text[consts.STACK_TRACK] = str(traceback.format_exc())
        event = timberlog_event_handler.create_event(consts.EVENT_TYPE_SPOT, text, consts.END_WITHOUT_START)
    else:
        curr_task = stack.pop()
        task_id = curr_task[consts.TASK_ID_INDEX]
        event = timberlog_event_handler.create_event(event_type, text, task_id=task_id)

    return event


def __generate_task_id(name):
    return name + '_' + str(int(round(time.time() * 1000))) + '_' + str(randint(0, 10000000))


def get_current_task_id():
    stack = __get_stack()
    curr_task_tuple = stack.peek()
    return curr_task_tuple[consts.TASK_ID_INDEX]


@optional_args_decorator
def timberlog_start(func, task_name=None, retention_days=None):
    @wraps(func)
    def wrapper(*args, **kwargs):
        with start_task(task_name if task_name else func.__name__, retention_days) as tl:
            globals = func.__globals__
            globals['tl'] = tl
            return func(*args, **kwargs)

    return wrapper